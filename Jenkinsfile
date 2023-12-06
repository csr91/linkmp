@Library('jobs-jenkins-library')

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

String projectName = "linkmp"
String label = "linkmp-builder-${UUID.randomUUID().toString().substring(0, 8)}"
String sonarqubeProjectKey = "com.jobint:${projectName}"

// Set quiet period
if (env.BRANCH_NAME == "master") {
    currentBuild.rawBuild.getParent().setQuietPeriod(30)
}

// Stop previous executions of the same branch (Except master)
jobExecution.stopPrevious()

podTemplate(cloud: 'jenkins-slaves-cluster',
        label: label,
        yaml: """
apiVersion: v1
kind: Pod
metadata:
  labels:
    builder: ${projectName}
spec:
  nodeSelector:
    builders: "true"
  containers:
  - name: gcloud-helm
    image: ${env.DOCKER_PUBLIC_REGISTRY}gcloud-helm:310.0.0-3.9.0
    command: ['cat']
    tty: true
  - name: dind
    image: ${env.DOCKER_PUBLIC_REGISTRY}docker:18.05-dind
    securityContext:
      privileged: true
  - name: gh
    image: ${env.DOCKER_PUBLIC_REGISTRY}dcycle/gh-cli:1.0
    tty: true
    command: ['cat']
"""
) {

    node(label) {

        def deployHelpers
        Map<String, String> chartVersions = [:]

        stage('Checkout') {
            // Checkout del proyecto
            checkout scm

            deployHelpers = load "helm/helpers.groovy"
        }

        stage('Publish docker images') {
            if (env.CHANGE_ID || env.BRANCH_NAME == "master") {
                github.withPRStatus(context: "docker",
                        pendingDescription: 'Images build pending',
                        successDescription: 'Images build OK',
                        failDescription: 'Error building images'
                ) {
                    String accessToken
                    container("gcloud-helm") {
                        // Get gcloud access token
                        accessToken = sh(returnStdout: true, script: "gcloud auth print-access-token | tr -d '\\n'")
                    }
                    container("dind") {
                        // Login docker to Google Cloud
                        sh "echo \"${accessToken}\" | docker login -u oauth2accesstoken --password-stdin https://us-east1-docker.pkg.dev"
                    }

                    dir("server") {
                        container("dind") {
                            String privateDockerRegistryPath = "jobint/linkmp"

                            sh "docker build . --pull -t ${env.DOCKER_PRIVATE_REGISTRY}${privateDockerRegistryPath}:${env.VERSION_PUBLISHED}"
                            sh "docker push ${env.DOCKER_PRIVATE_REGISTRY}${privateDockerRegistryPath}:${env.VERSION_PUBLISHED}"
                        }
                    }
                }
            } else {
                Utils.markStageSkippedForConditional(env.STAGE_NAME)
            }
        }

        stage('Bake Helm chart') {
            if (env.CHANGE_ID || (env.BRANCH_NAME == "master" && env.GIT_TAG != "")) {
                github.withPRStatus(context: "helm",
                        pendingDescription: 'Bake Helm chart pending',
                        successDescription: 'Bake Helm chart OK',
                        failDescription: 'Error baking Helm chart'
                ) {
                    dir("helm") {
                        container('gcloud-helm') {
                            deployHelpers.initHelm(repoName: "chartmuseum", repoUrl: "${env.CHARTMUSEUM_CORE_URL}")
                        }
                    }

                    dir("helm/linkmp") {
                        container('gcloud-helm') {
                            chartVersions["linkmp"] = deployHelpers.bakeAndPublishChart(
                                    applicationVersion: "${env.VERSION_PUBLISHED}",
                                    repoName: "chartmuseum"
                            )
                        }
                    }

                    container('gcloud-helm') {
                        deployHelpers.updateHelmRepositories()
                    }
                }
            } else {
                Utils.markStageSkippedForConditional(env.STAGE_NAME)
            }
        }

        stage('Deploy to Prepro') {
            if (deployToPrepro()) {
                github.withPRStatus(context: "deploy-prepro",
                        pendingDescription: 'Deploy pending',
                        successDescription: "Deployed to prepro",
                        failDescription: 'Deploy failed') {
                    configFileProvider([configFile(fileId: 'core-k8s-prepro', variable: 'CORE_K8S')]) {

                        load "${CORE_K8S}"

                        // Get Kubernetes credentials
                        container('gcloud-helm') {
                            deployHelpers.loginGCloud(
                                    cluster: "${env.K8S_CLUSTER}",
                                    project: "${env.GOOGLE_PROJECT}",
                                    zone: "${env.GOOGLE_ZONE}"
                            )

                            dir("helm/linkmp") {
                                deployHelpers.deploy(
                                        serviceName: "linkmp",
                                        chartName: "linkmp",
                                        repoName: "chartmuseum",
                                        version: "${env.VERSION_PUBLISHED}-${chartVersions.get("linkmp")}",
                                        environment: "prepro",
                                        k8sCluster: "${env.K8S_CLUSTER}",
                                        k8sNamespace: "${env.K8S_NAMESPACE}",
                                        googleZone: "${env.GOOGLE_ZONE}",
                                        configurationBranchName: "${env.CONFIGURATION_BRANCH}"
                                )
                            }
                        }
                    }
                }
            } else {
                Utils.markStageSkippedForConditional(env.STAGE_NAME)
            }
        }

        if (env.BRANCH_NAME == "master") {
            stage('Deploy to Prod') {
                if (env.GIT_TAG != "") {
                    configFileProvider([configFile(fileId: 'core-k8s-prod', variable: 'CORE_K8S')]) {

                        load "${CORE_K8S}"

                        // Get Kubernetes credentials
                        container('gcloud-helm') {
                            deployHelpers.loginGCloud(
                                    cluster: "${env.K8S_CLUSTER}",
                                    project: "${env.GOOGLE_PROJECT}",
                                    zone: "${env.GOOGLE_ZONE}"
                            )

                            dir("helm/linkmp") {
                                deployHelpers.deploy(
                                        serviceName: "linkmp",
                                        chartName: "linkmp",
                                        repoName: "chartmuseum",
                                        version: "${env.VERSION_PUBLISHED}-${chartVersions.get("linkmp")}",
                                        environment: "prod",
                                        k8sCluster: "${env.K8S_CLUSTER}",
                                        k8sNamespace: "${env.K8S_NAMESPACE}",
                                        googleZone: "${env.GOOGLE_ZONE}",
                                        configurationBranchName: "${env.CONFIGURATION_BRANCH}"
                                )
                            }
                        }
                    }

                    // Notificar via slack
                    slackSend channel: "#jobs-deploys", blocks: slack.getDeployMessage(application: projectName, version: env.VERSION_PUBLISHED, changelog: env.CHANGELOG)

                } else {
                    Utils.markStageSkippedForConditional(env.STAGE_NAME)
                }
            }
        }
    }
}

boolean deployToPrepro() {
    return (env.CHANGE_ID && (pullRequest.labels.findResult { it.toLowerCase() == "prepro" ? true : null })) || env.GIT_TAG != ""
}
