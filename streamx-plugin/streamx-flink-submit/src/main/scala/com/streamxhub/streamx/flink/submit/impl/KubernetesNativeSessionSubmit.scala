/*
 * Copyright (c) 2019 The StreamX Project
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.streamxhub.streamx.flink.submit.impl

import com.google.common.collect.Lists
import com.streamxhub.streamx.common.conf.ConfigConst.APP_WORKSPACE
import com.streamxhub.streamx.common.enums.ExecutionMode
import com.streamxhub.streamx.common.util.Logger
import com.streamxhub.streamx.flink.submit.`trait`.KubernetesNativeSubmitTrait
import com.streamxhub.streamx.flink.submit.{StopRequest, StopResponse, SubmitRequest, SubmitResponse}
import com.streamxhub.streamx.flink.packer.MavenTool
import org.apache.commons.lang.StringUtils
import org.apache.flink.api.common.JobID
import org.apache.flink.client.deployment.application.ApplicationConfiguration
import org.apache.flink.client.program.{ClusterClient, PackagedProgram, PackagedProgramUtils}
import org.apache.flink.configuration._
import org.apache.flink.kubernetes.KubernetesClusterDescriptor
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions

import scala.collection.JavaConversions._
import scala.util.Try

/**
 * kubernetes native session mode submit
 */
object KubernetesNativeSessionSubmit extends KubernetesNativeSubmitTrait with Logger {

  //noinspection DuplicatedCode
  override def doSubmit(submitRequest: SubmitRequest): SubmitResponse = {
    // require parameters
    assert(Try(submitRequest.clusterId.nonEmpty).getOrElse(false))

    val jobID = {
      if (StringUtils.isBlank(submitRequest.jobID)) new JobID()
      else JobID.fromHexString(submitRequest.jobID)
    }
    // extract flink configuration
    val flinkConfig = extractEffectiveFlinkConfig(submitRequest)
    // build fat-jar
    val fatJar = {
      val flinkLibs = extractProvidedLibs(submitRequest) :+ submitRequest.flinkUserJar
      val fatJarPath = s"$APP_WORKSPACE/${submitRequest.clusterId}/${jobID.toHexString}/flink-job.jar"
      MavenTool.buildFatJar(flinkLibs, fatJarPath)
    }

    // retrieve k8s cluster and submit flink job on session mode
    var clusterDescriptor: KubernetesClusterDescriptor = null
    var packageProgram: PackagedProgram = null
    var client: ClusterClient[String] = null
    try {
      clusterDescriptor = getK8sClusterDescriptor(flinkConfig)
      // build JobGraph
      packageProgram = PackagedProgram.newBuilder()
        .setJarFile(fatJar)
        .setEntryPointClassName(flinkConfig.get(ApplicationConfiguration.APPLICATION_MAIN_CLASS))
        .setArguments(
          flinkConfig.getOptional(ApplicationConfiguration.APPLICATION_ARGS)
            .orElse(Lists.newArrayList())
            : _*
        ).build()

      val jobGraph = PackagedProgramUtils.createJobGraph(
        packageProgram,
        flinkConfig,
        flinkConfig.getInteger(CoreOptions.DEFAULT_PARALLELISM),
        jobID,
        false)

      // retrieve client and submit JobGraph
      client = clusterDescriptor.retrieve(flinkConfig.getString(KubernetesConfigOptions.CLUSTER_ID)).getClusterClient
      val submitResult = client.submitJob(jobGraph)
      val jobId = submitResult.get().toString

      SubmitResponse(client.getClusterId, flinkConfig, jobId)

    } catch {
      case e: Exception =>
        logError(s"submit flink job fail in ${submitRequest.executionMode} mode")
        e.printStackTrace()
        throw e
    } finally {
      if (client != null) client.close()
      if (packageProgram != null) packageProgram.close()
      if (clusterDescriptor != null) clusterDescriptor.close()
    }
  }


  override def doStop(stopInfo: StopRequest): StopResponse = {
    doStop(ExecutionMode.KUBERNETES_NATIVE_SESSION, stopInfo)
  }


}