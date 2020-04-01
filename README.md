# Jenkins DevOps #

This repository contains Jenkins Pipelines, Terraform scripts, Kubernetes .yaml resources and other handy resources.

### MuleSoft Pipeline v3 ###

* It is build around Jenkins Shared Library
* Uses declarative syntax
* Supports Bitbucket Webhooks
* Has a Linter stage to ensure code quality
* Support PullRequests triggers
* directories: resources, var, src
* [Documentation](https://bookstack.kube.cloudapps.ms3-inc.com/books/jenkins/page/mulesoft-internal-project-pipeline)

### MuleSoft Pipeline v2 ###

* Common pipeline for MuleSoft API's
* Left here for backward compatibility
* Uses Poll SCM trigger
* Desinged for k8s cluster
* directory: mule

### Angular Pipeline v2 ###

* Common pipeline for Angular SPA
* Left here for backward compatibility
* Uses Poll SCM trigger
* directory: angular


### Best practices ###

* [IaC](https://en.wikipedia.org/wiki/Infrastructure_as_code)
* [DRY](https://en.wikipedia.org/wiki/Don%27t_repeat_yourself)
* [Jenkins Shared Libs](https://jenkins.io/doc/book/pipeline/shared-libraries/)

### Who do I talk to? ###

* Please join #m3-devops slack channel
