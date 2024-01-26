# JetBrains Space plugin for Jenkins

[![JetBrains incubator project](https://jb.gg/badges/incubator-flat-square.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

## About

This Jenkins plugin provides integration with JetBrains Space and enables the following use cases:
* Triggering jobs in Jenkins on new commits or merge request updates in JetBrains Space;
* Reporting build status to JetBrains Space (can be used in [Quality Gates for Merge Requests](https://www.jetbrains.com/help/space/branch-and-merge-restrictions.html#quality-gates-for-merge-requests));
* Using Jenkins jobs for [safe merging](https://www.jetbrains.com/help/space/branch-and-merge-restrictions.html#safe-merge) changes to the main branch in JetBrains Space;
* Posting messages to the merge request timeline in JetBrains Space on behalf of Jenkins integration;
* Hyperlinks to branches, files and diffs in JetBrains Space on the Changes page of a Jenkins build.

Report issues and enhancements in the GitHub issues.

## Requirements

* Jenkins version 2.401.3+
* Current JetBrains Space in cloud or Space On-Premises 2024.1+   

## License

Licensed under MIT, see [LICENSE](LICENSE.md)

## Table of Contents
- [Configuration](#configuration)
  - [Enable Jenkins integration in Space](#enable-jenkins-integration-in-space)
  - [Configure Space integration in Jenkins](#configure-space-integration-in-jenkins)
- [Usage](#usage)
  - [Triggering Builds](#triggering-builds)
  - [Checking out source code from Space](#checking-out-source-code-from-space)
  - [Hyperlinks to Space for build changes](#hyperlinks-to-space-for-build-changes)
  - [Posting build status to Space](#posting-build-status-to-space)
  - [Posting a message to the merge request timeline in Space](#posting-a-message-to-the-merge-request-timeline-in-space)
  - [Calling Space HTTP API from pipeline script](#calling-space-http-api-from-pipeline-script)
  - [Environment variables](#environment-variables)
- [Integrating Jenkins with JetBrains Space without installing the Jenkins plugin](#integrating-jenkins-with-jetbrains-space-without-installing-the-jenkins-plugin)
- [Contributing](#contributing)

## Configuration

Configuring integration consists of two parts, the first one done in JetBrains Space and the second one in Jenkins.

### Enable Jenkins integration in Space

**TODO: This section describes the current installation process on Space side, we will simplify it by hiding a concept of application from the user.
Will need to rewrite this section after we implement the simplified integrations management UI on Space side.**

Create an application. Navigate to **Extensibility > Applications**, press **New application** and enter the name for the application (for example, ”Jenkins”).

Configure permissions for the new application. Go to **Authorization** tab to authorize the application in Space projects, press
**Configure requirements** button in the **In-context Authorization** section and enable the following permissions:
  * Report external status checks
  * Read Git repositories
  * Write Git repositories *(if your Jenkins jobs will push tags or branches to git)*
  * Post comments to code reviews *(if your Jenkins jobs will post messages to merge request timeline in Space)*

Save changes after selecting all the required permissions.

Press **Authorize in project** button in the same section, pick a project in Space and press **Authorize** button in the popup.
If you want to use Jenkins integration in multiple Space projects, repeat this for each project.
If you don’t have administrator rights in those projects, you’ll also have to wait for the project administrator to approve permission request for your application.
Alternatively, you can grant these permissions to Jenkins integration globally, for all projects. Do this via **Configure** button in the **Global authorization** section on the same page.

Go to the **Permanent Tokens** tab and create a new permanent token for Jenkins to report external status checks to Space.
Copy the provided token, you’ll need to provide it later when setting up the integration on Jenkins side.

Go to the **Git Keys** tab and upload a new public SSH key that Jenkins will use to pull code from the Space git repository and optionally to push changes to it.
You'll need to provide the corresponding private key to Jenkins when setting up the integration on Jenkins side.

Go to the **Authentication** tab and find **Client ID** and **Client secret** values there. Those are the credentials used to access the Space HTTP API.
You'll need to provide them to Jenkins as well when setting up the integration on Jenkins side.

### Configure Space integration in Jenkins

Install this plugin to your Jenkins instance. This is done via **Jenkins > Manage Jenkins > Manage Plugins** menu in Jenkins.

**Note: the usage of plugin is recommended and makes integration much easier, but it is also possible to integrate Jenkins with Space without installing the plugin.
See [corresponding section](#integrating-jenkins-with-jetbrains-space-without-installing-the-jenkins-plugin) for more details.**

For establishing a connection with JetBrains Space, we will need to ad Space API credentials and SSH key to Jenkins. 
Go to **Jenkins > Manage Jenkins > Credentials** page, pick a domain to add credentials to (choose **System > Global credentials (unrestricted)** if in doubt) and add two credentials instances there.
Pick **JetBrains Space API credentials** kind for the first one and provide client id and secret values obtained from Space.
Also provide a meaningful description that identifies your Space instance, especially if you are going to add more than one Space instance to your Jenkins installation.

The second credentials instance should be of the **SSH Username with private key** kind. Provide private SSH key corresponding to the public key you've uploaded to JetBrains Space.
The **Username** should match the application name you've entered in JetBrains Space.

After creating credentials in Jenkins, we're ready to add the connection to Space itself. Go to **Jenkins > Manage Jenkins > System** page and find **JetBrains Space** section there.
Add a new connection, specify a meaningful name for it (especially if there will be more than one), enter the url of your Space organization and select API and SSH credentials from the dropdown lists.
Test your connection to Space by clickint the **Test API connection** button. If connection fails, check that you've specified the correct url and client id / secret credentials.
SSH connection to Space git repositories is not tested by this button.

![Setting up Space connection in Jenkins](docs/images/connection.png)

## Usage

### Triggering builds

Space plugin provides an option to trigger builds whenever new commits are pushed to a git repository or some changes are made to a merge request in Space.
This trigger is enabled by the **JetBrains Space webhook trigger** checkbox in the **Build Triggers** section of the job or pipeline configuration page.
For this trigger, you need to specify the Space connection, project and git repository to use. The list of projects and repositories for the dropdown lists
is fetched from Space API and lists only those projects that have been authorized for Jenkins integration while setting it up on Space side.

When build is triggered by new commits to repository, you can filter branches triggering the build by their names.
The text field **Branches** under the **Trigger on commits** option accepts a list of branch specs separated by a semicolon.
Each branch spec can contain `*` wildcards and should start with either `+` (include branches)  or `-` (exclude branches) symbol, excludes take precedence over includes.

When build is triggered by merge request changes, there are three event filters available:
* When **Build only after merge request is approved by all reviewers** checkbox is checked, builds will only be triggered for merge requests approved by all the invited reviewers.
  There should be at least one reviewer in a merge request to trigger the build.
  The build will be triggered when new commits are added to a merge request (provided they don't reset reviewers' approvals, that is all approvals are finalized) 
  or when the last reviewer approves the merge request;
* **Branch specs** filter has the same format as the one for triggering by commits, and is applied to the source branch of a merge request;
* **Title filter regex** allows triggering builds only for merge requests with title matching specified regular expression.
  A build will be triggered when merge request with title matching regex is created, when new commits are added to it or when non-matching merge request title is changed so that it now matches the regex; 

### Checking out source code from Space

The recommended way to check out sources to build from a git repository hosted in JetBrains Space is by using the SCM (Source Control Management) source provided by the plugin.

For Jenkins jobs, it is available under the **JetBrains Space** radio button in **Source Code Management** section of the job configuration page.
There are two options for checking out code from Space by using this SCM:
* **Post build status to Space** checkbox determines whether build status will be automatically reported to Space
  to be displayed as external check for commit (which can be further used for [Merge request quality gates](https://www.jetbrains.com/help/space/branch-and-merge-restrictions.html#quality-gates-for-merge-requests) or [Safe merge](https://www.jetbrains.com/help/space/branch-and-merge-restrictions.html#safe-merge) functionality in Space).
  When this checkbox is checked, Jenkins will report build status for the git commit currently built to Space twice - first as running when the build starts and then as succeeded, failed or terminated depending on the build outcome when it finishes.
* **Specify Space repository** optional section allows overriding Space connection, project and repository that build status is reported to.
  By default these parameters are taken from the build trigger settings. If build isn't triggered by the **JetBrains Space webhook trigger** then they must be specified in the source checkout settings, 
  otherwise build will fail at runtime.

![Checkout settings](docs/images/scm.png)

For Jenkins pipelines, you can invoke it with the standard `checkout` pipeline step by passing a parameterized instance of `SpaceGit` object to it:
```groovy
checkout SpaceGit()
checkout SpaceGit(postBuildStatusToSpace: false)
checkout SpaceGit(projectKey: 'PRJ', branches: [[name: 'refs/heads/feature-*']], repository: 'prj-main-repo', spaceConnection: 'Space')
```

The scripted form as described above, all being optional (but *spaceConnection*, *projectKey* and *repository* should be either all present or all omitted).
You can use Jenkins pipeline syntax generator to pick **checkout** step, choose **JetBrains Space** source, configure all the parameters in UI
and then generate a script for calling this step with the parameters configured.

There is also an option to check out sources from Space git repository using a standard Git SCM and providing git repo clone url and SSH key or HTTP password in place.
```groovy
checkout scmGit(branches: [[name: '*/master']], extensions: [], userRemoteConfigs: [[credentialsId: 'ssh-creds-for-space', url: 'ssh://git@git.jetbrains.space/my/proj/proj-repo.git']])
```

It works, but you have to copy repository url from the **Start coding** dialog in Space manually instead of just picking Space connection,
project and repository from dropdown lists. You will also loose some of the Space-specific environment variables in this case.

### Hyperlinks to Space for build changes

When a job or pipeline checks out source code from Space git repository using Space SCM as described in the section above,
the plugin will enrich commits and changes info on the **Changes** page of a job or pipeline by links to JetBrains Space for commits, files and file diffs.
This allows you to easily navigate to Space UI to view the commits being build, files that they modify and change diffs for these files.

If you are checking out source code by using standard Git SCM instead of Space SCM, you can also enable links to Space for the **Changes** page of your builds.
Choose the **JetBrains Space** option for the **Repository browser** field of the Git SCM and specify base repository URL in the form of `https://<your Space instance>/p/<project key>/repositories/<repository name>`

![Repository browser](docs/images/repo-browser.png)

The scripted form for such pipeline checkout step will have the `browser` parameter with `jbSpace` specified as its value:

```groovy
checkout scmGit(browser: jbSpace('http://my.jetbrains.space/p/prj/repositories/main-repo'), /* branches, userRemoteConfigs etc... */)
```

### Posting build status to Space

A job or pipeline in Jenkins will automatically post build status for the commit to Space if source code has been checked out with the [Space SCM](#checking-out-source-code-from-space) source.
Build will be reported as running upon source code checkout and completed, failed or terminated depending on the build outcome upon its completion.
Automatic build status posting is enough for most cases, but the plugin also defines a pipeline step for more granular control
over when and what status is posted to Space - the **postBuildStatusToSpace** pipeline step:

```groovy
postBuildStatusToSpace buildStatus: 'SUCCEEDED'
```

As usual, you can explore the parameters available and generate the script snippet on the **Pipeline syntax** page in Jenkins.
The only required parameter is **Build status to report** (or *buildStatus* in script representation).
There are also two optional sections that allow overriding Space connection parameters (Space instance, project and repository) and git commit and branch name that the build status is reported for.
When Space connection parameters aren't specified explicitly, they are taken either (in priority order) from the build trigger settings (if **JetBrains Space webhook trigger** is enabled for this job or pipeline)
or from the source code checkout settings (this can be either Space SCM or Git SCM, in the latter case Jenkins will try to infer configured Space connection based on the git clone repository url).

Once a build status has been reported to Space by invoking the **postBuildStatusToSpace** pipeline step, it won't be automatically reported 
when the build finishes not to overwrite the status reported by the step. Thus, you can use a number of **postBuildStatusToSpace** in your pipeline script
to report build failures for some cases and at the same time rely on the automatic build status posting to report the final outcome
when none of those cases have happened and thus no post build status steps have been called. 

### Posting a message to the merge request timeline in Space

Another pipeline step provided by the plugin allows posting a message to the merge request timeline in Space on behalf of Jenkins integration

```groovy
postReviewTimelineMessageToSpace 'Here are some details about how the build is going...'
```

You can use [markdown syntax](https://www.jetbrains.com/help/space/markdown-syntax.html) to add formatting to the message.
If your job or pipeline uses **JetBrains Space webhook trigger** listening to merge request updates, then message text is the only parameter required for the pipeline step.
Otherwise you will also need to provide the **mergeRequestNumber** parameter. The number of a merge request is part of its URL - `/p/<project key>/reviews/<number>/timeline`.

There is also an option to override Space connection and project for the unlikely case when the **JetBrains Space webhook trigger** or git checkout step settings of the job or pipeline cannot be used.

### Calling Space HTTP API from pipeline script

Space provides an extensive HTTP API to fetch or manipulate its data, described in detail at https://www.jetbrains.com/help/space/api.html.
Space plugin for Jenkins provides a pipeline step to easily perform calls to this API on behalf of Jenkins integration.
You pick one of the preconfigured Space connections, specify HTTP method, path and request body and the step takes care of proper authentication
and response JSON deserialization. The step returns parsed JSON response in the form of [JsonNode](https://fasterxml.github.io/jackson-databind/javadoc/2.8/com/fasterxml/jackson/databind/JsonNode.html) instance from the Jackson library.
You can then access various properties of the resulting JSON by using indexing into its properties.

```groovy
script {
    def result = callSpaceApi(httpMethod: 'GET', requestUrl: '/api/http/applications/me')
    echo result["name"].asText()
    echo result["createdAt"]["iso"].asText()
}
```

By default Space instance to perform HTTP request to is taken from the **JetBrains Space webhook trigger** or git checkout step settings of the job or pipeline.
There is also an option to override this choice.

**NOTE:** Make sure you grant Jenkins integration the permissions required to access all the API endpoints you intend to call from your pipeline scripts. 

### Environment variables

Space plugin provides a number of environment variables that can be used by the pipeline logic:
* `SPACE_URL` - base url of your Space organization;
* `SPACE_PROJECT_KEY` - Space project key;
* `SPACE_REPOSITORY_NAME` - git repository name

These three env variables are provided by the **JetBrains Space webhook trigger** or by the source code checkout step (code checkout settings take precedence over build trigger in case both have explicitly specified Space connection).

When a build is triggered by the Space merge request, those variables are also provided:
* `SPACE_MERGE_REQUEST_ID` - merge request identifier, can be used to query more details about the merge request from the Space HTTP API; 
* `SPACE_MERGE_REQUEST_NUMBER` - merge request number;
* `SPACE_MERGE_REQUEST_SOURCE_BRANCH` - name of the merge request source branch;
* `SPACE_MERGE_REQUEST_TARGET_BRANCH` - name of the merge request target branch;
* `SPACE_MERGE_REQUEST_TITLE`- merge request title;
* `SPACE_MERGE_REQUEST_URL` - url of the merge request page in Space; 

All the environment variables provided by the standard Git plugin (https://plugins.jenkins.io/git/#plugin-content-environment-variables) are also available as well when checking out source code using Space SCM.

## Integrating Jenkins with JetBrains Space without installing the Jenkins plugin

Installing this plugin to Jenkins is the recommended way to integrate Jenkins with JetBrains Space. If installing plugin is not possible for some reasons,
there is still a possibility to set up integration, although it would be limited and less convenient to use.

See more details and a sample of safe merge setup without intalling Jenkins plugin [here](docs/integration-without-plugin.md).

## Contributing

Refer to our [contribution guidelines](CONTRIBUTING.md)

