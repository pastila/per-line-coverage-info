package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitLabProject(
    val id: Long,
    val name: String,
    @SerialName("path_with_namespace") val pathWithNamespace: String,
    @SerialName("web_url") val webUrl: String,
)

@Serializable
data class GitLabPipeline(
    val id: Long,
    val sha: String,
    val ref: String,
    val status: String,
    @SerialName("web_url") val webUrl: String,
)

@Serializable
data class GitLabJob(
    val id: Long,
    val name: String,
    val status: String,
    val pipeline: GitLabJobPipeline,
    val artifacts: List<GitLabArtifact> = emptyList(),
)

@Serializable
data class GitLabJobPipeline(
    val id: Long,
)

@Serializable
data class GitLabArtifact(
    val filename: String,
    val size: Long,
)

data class PipelineCommit(
    val commitHash: String,
    val pipelineId: Long,
)
