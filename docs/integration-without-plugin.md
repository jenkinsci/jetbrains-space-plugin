### Preparing the integration

Configuring the integration on the Space side is exactly the same as with the Jenkins plugin. Please refer to the [corresponding instructions](../README.md#enable-jenkins-integration-in-space) for completing this step.

The only difference is that you will also need a permanent token for Jenkins to to report external status checks to Space.
While setting up an application in Space, go to the **Permanent Tokens** tab and create a new permanent token there.
Copy the token, you’ll need to provide it later when setting up the integration on the Jenkins side.

**TODO There will be a small difference on the Space side as well as soon as we implement simplified Jenkins integration management (checkbox "no Jenkins plugin", persistent token generation), so will need to document it.**

On the Jenkins side, proceed as follows:
* Add a Space API token to credentials in Jenkins. Navigate to **Manage Jenkins > Credentials**, pick a domain to add credentials to
  (choose **System > Global credentials (unrestricted)** if in doubt) and add a new credential there. Choose the type **Secret text** and paste the permanent token generated on the Space side into the **Secret** field.
* Generate an access token for Space to trigger a build in Jenkins.
  Go to your personal profile settings in Jenkins, find the **API tokens** section and create a new token.
  Copy the provided token. You’ll need it later when setting up Jenkins integration for a specific project in Space.
  Make sure you have the permissions to trigger the necessary builds in Jenkins, otherwise Space won’t be able to trigger them using your token as well.

### Safe merge setup example

On the Space side:
* Add a project secret for storing the Jenkins api token. Navigate to the project settings in Space, go to the **Secrets & parameters** tab
  and click **Create > Secret**. Specify the name for the secret (the next step assumes that you name it *jenkins-token*) and the value,
  which is the personal API token you’ve created in Jenkins earlier.
* Set up safe merge using Jenkins in git repository. In your git repository in Space, create a `safe-merge.json` file and specify Jenkins URL, job or pipeline name, and credentials there.
  If your Jenkins job is nested within a folder, property specify the path to the job using a slash as the separator for the **project** (like *Folder/SubFolder/Job*).
  Space will then trigger a build of this Jenkins job whenever a safe merge or dry run is invoked for a merge request in Space,
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
* Add the **GIT_BRANCH** parameter of type `string` to your pipeline. They will be passed from Space when triggering a build in Jenkins.
* Set up posting external commit check results as part of your Jenkins pipeline. Take the following code snippet as an example:

```groovy
def postBuildResultToSpace(result) {
    env.REQUEST_BODY = "{ \"branch\": \"$GIT_BRANCH\", \"executionStatus\": \"$result\", \"url\": \"$BUILD_URL\", \"externalServiceName\": \"Jenkins\", \"taskName\": \"$JOB_NAME\", \"taskId\": \"$JOB_NAME\", \"taskBuildId\": \"build-$BUILD_ID\" }"
    sh 'curl -s $SPACE_URL/api/http/projects/key:$SPACE_PROJECT/repositories/$SPACE_REPO/revisions/$GIT_COMMIT/external-checks -d \"$REQUEST_BODY\" -H \"Authorization: Bearer $SPACE_TOKEN\" -H \"Accept: application/json\" -H \"Content-Type: application/json\"'
}

pipeline {
    agent any

    // Specify Space url, project, repo and api token as environment variables for using in pipeline steps
    environment {
        SPACE_URL = "https://myorg.jetbrains.space"
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
                // Source code must be checked out from git before any build status can be reported
                // so that we can propagate commit id to the environment variable to be used for reporting build status.
                // Note also how the GIT_BRANCH env variable is used to specify refspec to fetch as well as branch name to check out from git.
                script {
                    def scmVars = checkout scmGit(branches: [[name: env.GIT_BRANCH]], extensions: [], userRemoteConfigs: [[credentialsId: 'ssh-creds-for-space', refspec: "+${env.GIT_BRANCH}:${env.GIT_BRANCH}", url: "ssh://git@git.jetbrains.space/myorg/${env.SPACE_PROJECT}/${env.SPACE_REPO}.git"]])
                    env.GIT_COMMIT = scmVars.GIT_COMMIT
                }
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

The function `postBuildResultToSpace` is responsible for posting the build execution status to Space. It uses a number of environment variables for building the request:

- GIT_BRANCH here is the parameter passed to Jenkins from Space when starting the build;
- GIT_COMMIT env variable is filled from the results of the source code checkout step;
- BUILD_URL, JOB_NAME and BUILD_ID are built-in env variables provided by Jenkins;
- SPACE_URL, SPACE_PROJECT and SPACE_REPO are env variables containing your Space organization URL, Space project key, and Space repository name. In this example they are specified within the pipeline script itself, but they could also be specified elsewhere in Jenkins;
- SPACE_TOKEN is the env variable containing Space API token from credentials.

The function first builds JSON for the request body using Groovy string interpolation (note double quotes) and stores this JSON into the environment variable to be used in the shell script. Then it executes the shell script with the env variables being substituted by the shell itself (note single quotes). String interpolation and quotes escaping is tricky here, but for security reasons it is important that Groovy string interpolation is not used to generate the shell command containing the secret (Space token).
