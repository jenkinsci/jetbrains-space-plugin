### Preparing the integration

Configuring the integration on Space side is exactly the same in this case, so refer to the [corresponding section](../README.md#enable-jenkins-integration-in-space) for this.

**TODO There will be a small difference on the Space side as well as soon as we implement simplified Jenkins integration management (checkbox "no Jenkins plugin", persistent token generation), so will need to document it.**

On Jenkins side do the following:
* Add Space api token to credentials in Jenkins. Navigate to **Manage Jenkins > Credentials**, pick a domain to add credentials to
  (choose **System > Global credentials (unrestricted)** if in doubt) and add a new credential there. Choose **Secret text** kind and paste the permanent token generated on the Space side into the **Secret** field.
* Generate an access token for Space to trigger build in Jenkins.
  Go to your personal profile settings in Jenkins, find **API tokens** section and create a new token.
  Copy the provided token, you’ll need it later when setting up Jenkins integration for a specific project in Space.
  Make sure you have the permissions to trigger the necessary builds in Jenkins, otherwise Space won’t be able to trigger them using your token as well.

### Sample of safe merge setup

On the Space side:
* Add project secret for storing Jenkins api token. Navigate to the project settings in Space, go to the **Secrets & parameters** tab
  and click **Create > Secret**. Specify the name for the secret (the next step assumes you call it *jenkins-token*) and the value,
  which is the personal API token you’ve created in Jenkins earlier.
* Set up safe merge using Jenkins in git repository. In your git repo in Space, create `safe-merge.json` file and specify Jenkins url, job or pipeline name and credentials there.
  If your Jenkins job is nested within a folder, property specify path to the job using slash as separator for the **project** (like *Folder/SubFolder/Job*).
  Space will then trigger a build of this Jenkins job whenever safe merge or dry run is invoked for a merge request in Space,
  passing the branch name and revision id as “GIT_BRANCH” and GIT_COMMIT parameters.

```json
{
    "version": "1.0",
    "builds": [
        {
            "jenkins": {
                "project": "Folder/Pipeline",
                "url": "https://jenkins.mycompany.com",
                "userName": "user",
                "apiToken": "${jenkins-token}"
            }
        }
    ]
}
```

On the Jenkins side:
* Add **GIT_BRANCH** and **GIT_COMMIT** parameters to your pipeline. They will be passed by Space when triggering a build in Jenkins.
* Set up posting external commit check results as part of your Jenkins pipeline. Take the following code snippet as a sample:

```groovy
def postBuildResultToSpace(result) {
    env.REQUEST_BODY = "{ \"branch\": \"$GIT_BRANCH\", \"executionStatus\": \"$result\", \"url\": \"$BUILD_URL\", \"externalServiceName\": \"Jenkins\", \"taskName\": \"$JOB_NAME\", \"taskId\": \"$JOB_NAME\", \"taskBuildId\": \"build-$BUILD_ID\" }"
    sh 'curl -s $SPACE_URL/api/http/projects/key:$SPACE_PROJECT/repositories/$SPACE_REPO/revisions/$GIT_COMMIT/external-checks -d \"$REQUEST_BODY\" -H \"Authorization: Bearer $SPACE_TOKEN\" -H \"Accept: application/json\" -H \"Content-Type: application/json\"'
}

pipeline {
    agent any

    // Specify Space url, project, repo and api token as environment variables for using in pipeline steps
    environment {
        SPACE_URL = "https://org.jetbrains.space"
        SPACE_PROJECT = "PRJ"
        SPACE_REPO = "repo"
        SPACE_TOKEN = credentials('jetbrains.space.token')
    }

    stages {
        // Before performing any build actions, report to Space that build has started.
        // When Space enqueues build for execution, it only gets a link to created queue item as a result.
        // Notifying that build has started enables Space to match this queue item with a started build instance. 
        // This is an optional, but recommended step.
        stage('Report build started') {
            steps {
                postBuildResultToSpace("RUNNING")
            }
        }
				// Do your build
        stage('Do build') {
            steps {
                sh 'sleep 10'
            }
        }
    }
    
    // Handle all the possible outcomes of the pipeline execution
    // and report them to Space
    post {
        success {
            script {
                postBuildResultToSpace("SUCCEEDED")
            }
        }
        failure {
            script {
                postBuildResultToSpace("FAILED")
            }
        }
        unstable {
            script {
                postBuildResultToSpace("FAILED")
            }
        }
        aborted {
            script {
                postBuildResultToSpace("TERMINATED")
            }
        }
    }
}
```

The function `postBuildResultToSpace` is responsible for posting build execution status to Space. It uses a number of environment variables for building the request:

- GIT_BRANCH and GIT_COMMIT here are the parameters passed to Jenkins from Space when starting the build;
- BUILD_URL, JOB_NAME and BUILD_ID are built-in env variables provided by Jenkins;
- SPACE_URL, SPACE_PROJECT and SPACE_REPO are env variables containing your Space organization url, Space project key and Space repo name. In this example they are specified within the pipeline script itself, but they could also be specified elsewhere in Jenkins.
- SPACE_TOKEN is the env variable containing Space API token from credentials

The function first it builds JSON for the request body by using Groovy string interpolation (note double qoutes) and stores this JSON into the environment variable to be used in shell script. Then it executes shell script with env variables being substituted by the shell itself (note single qoutes). String interpolation and quotes escaping is tricky here, but for security reasons it is important that Groovy string interpolation is not used to generate shell command containing the secret (Space token).
