## Contributing to the Plugin

Plugin source code is hosted on [GitHub](https://github.com/jenkinsci/jetbrains-space-plugin).
New feature proposals and bug fix proposals should be submitted as
[GitHub pull requests](https://help.github.com/articles/creating-a-pull-request).
Fork the repository on GitHub, prepare your change on your forked
copy, and submit a pull request (see [here](https://github.com/jenkinsci/gitlab-plugin/pulls) for open pull requests). Your pull request will be evaluated by the [plugin's CI job](https://ci.jenkins.io/blue/organizations/jenkins/Plugins%2Fgitlab-plugin/).

Report issues and enhancements in the GitHub issues.

## Run and test locally

The prerequisites for Java and Maven are documented on the [preparation](https://www.jenkins.io/doc/developer/tutorial/prepare/) page on jenkins.io.

To run Jenkins locally with the plugin installed, run the following command line command
```console
$ mvn hpi:run
```
```text
...	
INFO: Jenkins is fully up and running
```

Then open <http://localhost:8080/jenkins/> to test the plugin locally.

To debug Jenkins with the plugin installed, run the following command instead
```console
mvnDebug hpi:run
```
```text
Preparing to execute Maven in debug mode
Listening for transport dt_socket at address: 8000
```

Maven will start a lightweight launcher process and wait for the debugger to attach at the specified port. Attach to the Java process using your IDE and
wait for it to compile your plugin and run Jenkins.