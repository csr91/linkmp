import java.text.SimpleDateFormat

def initHelm(String repoName, String repoUrl) {
    sh "helm repo add ${repoName} ${repoUrl}"
    sh "helm plugin install https://github.com/chartmuseum/helm-push"
}

def initHelm(Map<String, String> args) {
    initHelm(args.get("repoName"), args.get("repoUrl"))
}

def bakeAndPublishChart(String applicationVersion, String repoName) {
    String chartName = sh(returnStdout: true, script: "sed -n 's/^name: //p' Chart.yaml | tr -d '\n'")
    String chartVersion = sh(returnStdout: true, script: "sed -n 's/^version: //p' Chart.yaml | tr -d '\n'")
    sh "helm package . --app-version ${applicationVersion} --version ${applicationVersion}-${chartVersion}"
    sh "helm cm-push --force ${chartName}-${applicationVersion}-${chartVersion}.tgz ${repoName}"

    return chartVersion
}

def bakeAndPublishChart(Map<String, String> params) {
    return bakeAndPublishChart(params.get("applicationVersion"), params.get("repoName"))
}

def updateHelmRepositories() {
    sh "helm repo update"
}

def deploy(String serviceName, String repoName, String chartName, String version, String environment, String k8sCluster, String k8sNamespace, String googleZone, String branchName = "", String configurationBranchName = "", String extraValues = "", Map<String, String> extraParameters = [:]) {
    String fullServiceName = "${serviceName}-${environment}"
    if(environment == "qa") {
        fullServiceName = "${fullServiceName}-${branchName.toLowerCase()}"
    }

    String helmParameters = "--install --debug --atomic --wait --timeout 900s"
    helmParameters = "${helmParameters} --namespace ${k8sNamespace}"
    helmParameters = "${helmParameters} --set kubernetes.name=${k8sCluster}"
    helmParameters = "${helmParameters} --set kubernetes.zone=${googleZone}"
    if(environment == "qa") {
        helmParameters = "${helmParameters} --set global.identifier=${branchName.toLowerCase()}"
    }
    if (environment != "prod" && configurationBranchName != "") {
        helmParameters = "${helmParameters} --set application.configuration_branch_name=${configurationBranchName}"
    }
    helmParameters = "${helmParameters} -f values/values.${environment}.yaml"
    if(extraValues != "") {
        helmParameters = "${helmParameters} -f values/values.${extraValues}.yaml"
    }
    if(!extraParameters.isEmpty()) {
        for(e in extraParameters) {
            helmParameters = "${helmParameters} --set ${e.key}=${e.value}"
        }
    }

    lock("${fullServiceName}") {
        sh "helm upgrade ${fullServiceName} ${repoName}/${chartName} --version ${version} ${helmParameters}"
    }
}

def deploy(Map<String, Object> params) {
    return deploy(params.get("serviceName"), params.get("repoName"), params.get("chartName"), params.get("version"), params.get("environment"), params.get("k8sCluster"),
            params.get("k8sNamespace"), params.get("googleZone"), params.getOrDefault("branchName", ""), params.getOrDefault("configurationBranchName", ""), params.getOrDefault("extraValues", ""), params.getOrDefault("extraParameters", [:]))
}

def loginGCloud(String cluster, String project, String zone) {
    sh "gcloud container clusters get-credentials ${cluster} --project=${project} --zone=${zone}"
}

def loginGCloud(Map<String, String> params) {
    return loginGCloud(params.get("cluster"), params.get("project"), params.get("zone"))
}

def notifyNewRelic(String version, String credentials, String appConfig) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    sdf.setTimeZone(TimeZone.getTimeZone("GMT"))
    String buildTime = sdf.format(new Date())

    withCredentials([string(credentialsId: credentials, variable: 'NEWRELIC_API_KEY')]) {

        configFileProvider([configFile(fileId: appConfig, variable: 'NEWRELIC_APP_PROP')]) {
            def newRelicAppProperties = readProperties file: "$NEWRELIC_APP_PROP"

            try {
                timeout(time: 1, unit: 'MINUTES') {
                    sh "curl -i -f -X POST 'https://api.newrelic.com/v2/applications/${newRelicAppProperties.appId}/deployments.json' " +
                            "-H 'X-Api-Key: $NEWRELIC_API_KEY' " +
                            "-H 'Content-Type: application/json' " +
                            "-d '{ \"deployment\": { " +
                            "\"revision\": \"${version}\", " +
                            "\"changelog\": \"\", " +
                            "\"description\": " +
                            "\"Deploy of version ${version}\", " +
                            "\"user\": \"\", " +
                            "\"timestamp\": \"${buildTime}\" " +
                            "} }'"
                }
            } catch(Exception _) {}
        }
    }
}

def notifyNewRelic(Map<String, String> params) {
    return notifyNewRelic(params.get("version"), params.get("credentials"), params.get("appConfig"))
}

return this
