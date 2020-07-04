package com.streamxhub.flink.core

import com.streamxhub.common.conf.ConfigConst._
import com.streamxhub.common.util.{Logger, PropertiesUtils}
import org.apache.flink.api.common.ExecutionConfig
import org.apache.flink.api.common.restartstrategy.RestartStrategies
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.configuration.Configuration
import org.apache.flink.contrib.streaming.state.{DefaultConfigurableOptionsFactory, RocksDBStateBackend}
import org.apache.flink.runtime.state.filesystem.FsStateBackend
import org.apache.flink.runtime.state.memory.MemoryStateBackend
import org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment
import org.apache.flink.streaming.api.{CheckpointingMode, TimeCharacteristic}
import com.streamxhub.flink.core.enums.{StateBackend => XStateBackend}
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment

import scala.collection.JavaConversions._
import scala.util.Try

class FlinkInitializer(args: Array[String]) extends Logger {

  var configFun:(StreamExecutionEnvironment, ParameterTool) => Unit = _

  def this(args: Array[String],config: (StreamExecutionEnvironment, ParameterTool) => Unit) = {
    this(args)
    configFun = config
  }

  val parameter: ParameterTool = initParameter()

  var streamEnv: StreamExecutionEnvironment = _

  private[this] def initParameter(): ParameterTool = {
    val argsMap = ParameterTool.fromArgs(args)
    val config = argsMap.get(KEY_FLINK_APP_CONF, null) match {
      case null | "" => throw new ExceptionInInitializerError("[StreamX] Usage:can't fond config,please set \"--flink.conf $path \" in main arguments")
      case file => file
    }
    val configFile = new java.io.File(config)
    require(configFile.exists(), s"[StreamX] Usage:flink.conf file $configFile is not found!!!")
    val configArgs = config.split("\\.").last match {
      case "properties" => PropertiesUtils.fromPropertiesFile(configFile.getAbsolutePath)
      case "yml" | "yaml" => PropertiesUtils.fromYamlFile(configFile.getAbsolutePath)
      case _ => throw new IllegalArgumentException("[StreamX] Usage:flink.conf file error,muse be properties or yml")
    }
    ParameterTool.fromMap(configArgs).mergeWith(argsMap).mergeWith(ParameterTool.fromSystemProperties())
  }

  private[core] def initStreamEnv() = {

    def envConfig(): Unit = {
      //init env...
      val parallelism = Try(parameter.get(KEY_FLINK_PARALLELISM).toInt).getOrElse(ExecutionConfig.PARALLELISM_DEFAULT)
      val restartAttempts = Try(parameter.get(KEY_FLINK_RESTART_ATTEMPTS).toInt).getOrElse(3)
      val delayBetweenAttempts = Try(parameter.get(KEY_FLINK_DELAY_ATTEMPTS).toInt).getOrElse(50000)
      val timeCharacteristic = Try(TimeCharacteristic.valueOf(parameter.get(KEY_FLINK_WATERMARK_TIME_CHARACTERISTIC))).getOrElse(TimeCharacteristic.EventTime)
      val interval = Try(parameter.get(KEY_FLINK_WATERMARK_INTERVAL).toInt).getOrElse(0)
      if (interval > 0) {
        streamEnv.getConfig.setAutoWatermarkInterval(interval)
      }
      streamEnv.setParallelism(parallelism)
      streamEnv.setStreamTimeCharacteristic(timeCharacteristic)
      //重启策略.
      streamEnv.getConfig.setRestartStrategy(RestartStrategies.fixedDelayRestart(restartAttempts, delayBetweenAttempts))
    }

    def checkpoint(): Unit = {
      //checkPoint,从配置文件读取是否开启checkpoint,默认不启用.
      val enableCheckpoint = Try(parameter.get(KEY_FLINK_CHECKPOINTS_ENABLE).toBoolean).getOrElse(false)
      if (enableCheckpoint) {
        val cpInterval = Try(parameter.get(KEY_FLINK_CHECKPOINTS_INTERVAL).toInt).getOrElse(1000)
        val cpMode = Try(CheckpointingMode.valueOf(parameter.get(KEY_FLINK_CHECKPOINTS_MODE))).getOrElse(CheckpointingMode.EXACTLY_ONCE)
        val cpCleanUp = Try(ExternalizedCheckpointCleanup.valueOf(parameter.get(KEY_FLINK_CHECKPOINTS_CLEANUP))).getOrElse(ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION)
        val cpTimeout = Try(parameter.get(KEY_FLINK_CHECKPOINTS_TIMEOUT).toInt).getOrElse(60000)
        val cpMaxConcurrent = Try(parameter.get(KEY_FLINK_CHECKPOINTS_MAX_CONCURRENT).toInt).getOrElse(1)
        val cpMinPauseBetween = Try(parameter.get(KEY_FLINK_CHECKPOINTS_MIN_PAUSEBETWEEN).toInt).getOrElse(500)

        //默认:开启检查点,1s进行启动一个检查点
        streamEnv.enableCheckpointing(cpInterval)
        //默认:模式为exactly_one，精准一次语义
        streamEnv.getCheckpointConfig.setCheckpointingMode(cpMode)
        //默认: 检查点之间的时间间隔为1s【checkpoint最小间隔】
        streamEnv.getCheckpointConfig.setMinPauseBetweenCheckpoints(cpMinPauseBetween)
        //默认:检查点必须在1分钟之内完成，或者被丢弃【checkpoint超时时间】
        streamEnv.getCheckpointConfig.setCheckpointTimeout(cpTimeout)
        //默认:同一时间只允许进行一次检查点
        streamEnv.getCheckpointConfig.setMaxConcurrentCheckpoints(cpMaxConcurrent)
        //默认:被cancel会保留Checkpoint数据
        streamEnv.getCheckpointConfig.enableExternalizedCheckpoints(cpCleanUp)
      }

      val stateBackend = Try(XStateBackend.withName(parameter.get(KEY_FLINK_STATE_BACKEND))).getOrElse(null)

      //checkpoint,not LocalModel
      if (!streamEnv.getJavaEnv.isInstanceOf[LocalStreamEnvironment] && stateBackend != null) {
        val dataDir = if (stateBackend == XStateBackend.jobmanager) null else {
          /**
           * dataDir如果从配置文件中读取失败(key:flink.checkpoints.dir),则尝试从flink-conf.yml中读取..
           */
          Try(parameter.get(KEY_FLINK_CHECKPOINTS_DIR)).getOrElse(null) match {
            //从flink-conf.yaml中读取.
            case null =>
              logWarn("[StreamX] can't found flink.checkpoints.dir from properties,now try found from flink-conf.yaml")
              val flinkHome = System.getenv("FLINK_HOME")
              require(flinkHome != null, "[StreamX] FLINK_HOME is not defined in your system.")
              val flinkConf = s"$flinkHome/conf/flink-conf.yaml"
              val prop = PropertiesUtils.fromYamlFile(flinkConf)
              //从flink-conf.yaml中读取,key: state.checkpoints.dir
              val dir = prop("state.checkpoints.dir")
              require(dir != null, s"[StreamX] can't found flink.checkpoints.dir from $flinkConf ")
              logInfo(s"[StreamX] stat.backend: flink.checkpoints.dir found in flink-conf.yaml,$dir")
              dir
            case dir =>
              logInfo(s"[StreamX] stat.backend: flink.checkpoints.dir found in properties,$dir")
              dir
          }
        }

        stateBackend match {
          /**
           * The size of each individual state is by default limited to 5 MB. This value can be increased in the constructor of the MemoryStateBackend.
           * Irrespective of the configured maximal state size, the state cannot be larger than the akka frame size (see <a href="https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/config.html">Configuration</a>).
           * The aggregate state must fit into the JobManager memory.
           */
          case XStateBackend.jobmanager =>
            logInfo(s"[StreamX] stat.backend Type: jobmanager...")
            //default 5 MB,cannot be larger than the akka frame size
            val maxMemorySize = Try(parameter.get(KEY_FLINK_STATE_BACKEND_MEMORY).toInt).getOrElse(MemoryStateBackend.DEFAULT_MAX_STATE_SIZE)
            val async = Try(parameter.get(KEY_FLINK_STATE_BACKEND_ASYNC).toBoolean).getOrElse(false)
            val ms = new MemoryStateBackend(maxMemorySize, async)
            streamEnv.setStateBackend(ms)
          case XStateBackend.filesystem =>
            logInfo(s"[StreamX] stat.backend Type: filesystem...")
            val async = Try(parameter.get(KEY_FLINK_STATE_BACKEND_ASYNC).toBoolean).getOrElse(false)
            val fs = new FsStateBackend(dataDir, async)
            streamEnv.setStateBackend(fs)
          case XStateBackend.rocksdb =>
            logInfo(s"[StreamX] stat.backend Type: rocksdb...")
            // 默认开启增量.
            val incremental = Try(parameter.get(KEY_FLINK_STATE_BACKEND_INCREMENTAL).toBoolean).getOrElse(true)
            val rs = new RocksDBStateBackend(dataDir, incremental)
            /**
             * @see <a href="https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/config.html#rocksdb-configurable-options"/>Flink Rocksdb Config</a>
             */
            val map = new java.util.HashMap[String, Object]()
            val skipKey = List(KEY_FLINK_STATE_BACKEND_ASYNC, KEY_FLINK_STATE_BACKEND_INCREMENTAL, KEY_FLINK_STATE_BACKEND_MEMORY, KEY_FLINK_STATE_ROCKSDB)
            parameter.getProperties.filter(_._1.startsWith(KEY_FLINK_STATE_ROCKSDB)).filterNot(x => skipKey.contains(x._1)).foreach(x => map.put(x._1, x._2))
            if (map.nonEmpty) {
              val optionsFactory = new DefaultConfigurableOptionsFactory
              val config = new Configuration()
              val confData = classOf[Configuration].getDeclaredField("confData")
              confData.setAccessible(true)
              confData.set(map, config)
              optionsFactory.configure(config)
              rs.setOptions(optionsFactory)
            }
            streamEnv.setStateBackend(rs)
          case _ =>
            logError("[StreamX] usage error!!! stat.backend must be (jobmanager|filesystem|rocksdb)")
        }
      }
    }

    this.streamEnv = StreamExecutionEnvironment.getExecutionEnvironment
    envConfig()
    checkpoint()
    if (configFun != null) {
      configFun(this.streamEnv, this.parameter)
    }
    this.streamEnv.getConfig.setGlobalJobParameters(parameter)
    this.streamEnv
  }


}