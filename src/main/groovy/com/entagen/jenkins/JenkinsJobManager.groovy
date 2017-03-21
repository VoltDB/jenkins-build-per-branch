package com.entagen.jenkins

class JenkinsJobManager {
    String templateJobPrefix
    String templateBranchName
    String gitUrl
    String nestedView
    String jenkinsUrl
    String branchNameRegex
    String jenkinsUser
    String jenkinsPassword
    
    Boolean dryRun = false
    Boolean noViews = false
    Boolean noDelete = false
    Boolean startOnCreate = false

    JenkinsApi jenkinsApi
    GitApi gitApi

    String ALL_RELEASES = "release-\\d+\\.\\d+\\.x(?<!7\\.1\\.x)"
    String ALL_BACKPORTS = "-backportv\\d+\\.\\d+"
    String BACKPORT = "-backportv"

    JenkinsJobManager(Map props) {
        for (property in props) {
            this."${property.key}" = property.value
        }
        initJenkinsApi()
        initGitApi()
    }

    void syncWithRepo() {
        List<String> allBranchNames = gitApi.branchNames
        List<String> allJobNames = jenkinsApi.jobNames

        // ensure that there is at least one job matching the template pattern, collect the set of template jobs
        List<TemplateJob> templateJobs = findRequiredTemplateJobs(templateBranchName, allJobNames)
        Map<String, List> templates = [ "${templateBranchName}" : templateJobs.clone() ]
        List<TemplateJob> moreTemplateJobs = findRequiredTemplateJobs("$ALL_RELEASES", allJobNames)
        moreTemplateJobs.each() { it ->
            if (it.baseJobName ==~ /^branch-\d+-.*/ && it.templateBranchName.startsWith("release-")) {
                List<TemplateJob> t = templates.get(it.templateBranchName, [])
                t.add(it)
            }
        }
        // a list of all the template jobs
        templateJobs.addAll(moreTemplateJobs)

        /*
        template   template jobs                        non-template jobs

        master     branch-\d-.*-master$                 branch-\d-.*(branch)
                                                        including -release-x.y.z
                                                        all-master$
                                                        all-backportv\d+\.\d+ (all matches)

        -backportvx.y      branch-\d-.*-release-x.y$    branch-\d-.* contiaining -backportvx.y (inclusive)
                                                        --or--
                                                        all-master
                                                        all-release-\d.\d
                                                        filter -backportvx.y

        */

        // create any missing template jobs and delete any jobs matching the template patterns that no longer have branches
        templates.each() { template, jobs ->
            syncJobs(allBranchNames, allJobNames, template, jobs)
        }

        // create any missing branch views, scoped within a nested view if we were given one
        if (!noViews) {
            syncViews(allBranchNames)
        }
    }

    public String getBackportVersion(String release) {
        if (release == "master") {
            // all branches with -backportv tags
            return "${BACKPORT}\\d+\\.\\d+"
        } else {
            // all branches with a particular -backportv version tag
            def rx = (release =~ /release-(\d+\.\d+)/)
            rx.matches()
            assert rx[0][1]
            return "${BACKPORT}${rx[0][1]}"
            }
        }

    public void syncJobs(List<String> allBranchNames, List<String> allJobNames, String template, List<TemplateJob> templateJobs) {
        List<String> currentTemplateDrivenJobNames = templateDrivenJobNames(template, templateJobs, allJobNames)
        List<String> nonTemplateBranchNames = allBranchNames - template
        if (template == "master") {
            nonTemplateBranchNames.removeAll { it ==~ /.*${getBackportVersion(template)}.*/ }
            nonTemplateBranchNames.removeAll { it ==~ /.*$ALL_RELEASES$/ }
        } else {
            nonTemplateBranchNames.retainAll { it ==~ /.*${getBackportVersion(template)}.*/ }
        }
        List<ConcreteJob> expectedJobs = this.expectedJobs(templateJobs, nonTemplateBranchNames)

        createMissingJobs(expectedJobs, currentTemplateDrivenJobNames, templateJobs)
        if (!noDelete) {
            deleteDeprecatedJobs(currentTemplateDrivenJobNames - expectedJobs.jobName)
        }
    }

    public void createMissingJobs(List<ConcreteJob> expectedJobs, List<String> currentJobs, List<TemplateJob> templateJobs) {
        List<ConcreteJob> missingJobs = expectedJobs.findAll { !currentJobs.contains(it.jobName) }
        if (!missingJobs) return

        for(ConcreteJob missingJob in missingJobs) {
            println "Creating missing job: ${missingJob.jobName} from ${missingJob.templateJob.jobName}"
            jenkinsApi.cloneJobForBranch(missingJob, templateJobs)
            if (startOnCreate) {
                jenkinsApi.startJob(missingJob)
            }
        }

    }

    public void deleteDeprecatedJobs(List<String> deprecatedJobNames) {
        if (!deprecatedJobNames) return
        println "Deleting deprecated jobs:\n\t${deprecatedJobNames.join('\n\t')}"
        deprecatedJobNames.each { String jobName ->
            jenkinsApi.deleteJob(jobName)
        }
    }

    public List<ConcreteJob> expectedJobs(List<TemplateJob> templateJobs, List<String> branchNames) {
        branchNames.collect { String branchName ->
            templateJobs.collect { TemplateJob templateJob -> templateJob.concreteJobForBranch(branchName) }
        }.flatten()
    }

    public List<String> templateDrivenJobNames(String template, List<TemplateJob> templateJobs, List<String> allJobNames) {
        List<String> templateJobNames = templateJobs.jobName
        List<String> templateBaseJobNames = templateJobs.baseJobName

        // don't want actual template jobs, just the jobs that were created from the templates
        if (template == "master") {
            def result = (allJobNames - templateJobNames).findAll { String jobName ->
                templateBaseJobNames.find { String baseJobName -> jobName.startsWith(baseJobName) && !jobName.find(/.*${getBackportVersion(template)}.*/) }
            }
            result.removeAll { it ==~ /.*${ALL_RELEASES}$/ }
            return result
        } else {
            return (allJobNames - "master").findAll { String jobName ->
                templateBaseJobNames.find { String baseJobName -> jobName.find(/${getBackportVersion(template)}/) }
            }
        }
    }

    List<TemplateJob> findRequiredTemplateJobs(String templateBranchName, List<String> allJobNames) {
        String regex = /^($templateJobPrefix-.*)-($templateBranchName)$/

        List<TemplateJob> templateJobs = allJobNames.findResults { String jobName ->
            TemplateJob templateJob = null
            jobName.find(regex) { full, baseJobName, branchName ->
                templateJob = new TemplateJob(jobName: full, baseJobName: baseJobName, templateBranchName: branchName)
            }
            return templateJob
        }

        assert templateJobs?.size() > 0, "Unable to find any jobs matching template regex: $regex\nYou need at least one job to match the templateJobPrefix and templateBranchName suffix arguments"
        return templateJobs
    }

    public void syncViews(List<String> allBranchNames) {
        List<String> existingViewNames = jenkinsApi.getViewNames(this.nestedView)
        List<BranchView> expectedBranchViews = allBranchNames.collect { String branchName ->
            new BranchView(branchName: branchName, templateJobPrefix: this.templateJobPrefix) }

        List<BranchView> missingBranchViews = expectedBranchViews.findAll { BranchView branchView -> !existingViewNames.contains(branchView.viewName)}
        addMissingViews(missingBranchViews)

        if (!noDelete) {
            List<String> deprecatedViewNames = getDeprecatedViewNames(existingViewNames, expectedBranchViews)
            deleteDeprecatedViews(deprecatedViewNames)
        }
    }

    public void addMissingViews(List<BranchView> missingViews) {
        println "Missing views: $missingViews"
        for (BranchView missingView in missingViews) {
            jenkinsApi.createViewForBranch(missingView, this.nestedView)
        }
    }

    public List<String> getDeprecatedViewNames(List<String> existingViewNames, List<BranchView> expectedBranchViews) {
         return existingViewNames?.findAll { it.startsWith(this.templateJobPrefix) } - expectedBranchViews?.viewName ?: []
    }

    public void deleteDeprecatedViews(List<String> deprecatedViewNames) {
        println "Deprecated views: $deprecatedViewNames"

        for(String deprecatedViewName in deprecatedViewNames) {
            jenkinsApi.deleteView(deprecatedViewName, this.nestedView)
        }

    }

    JenkinsApi initJenkinsApi() {
        if (!jenkinsApi) {
            assert jenkinsUrl != null
            if (dryRun) {
                println "DRY RUN! Not executing any POST commands to Jenkins, only GET commands"
                this.jenkinsApi = new JenkinsApiReadOnly(jenkinsServerUrl: jenkinsUrl)
            } else {
                this.jenkinsApi = new JenkinsApi(jenkinsServerUrl: jenkinsUrl)
            }

            if (jenkinsUser || jenkinsPassword) this.jenkinsApi.addBasicAuth(jenkinsUser, jenkinsPassword)
        }

        return this.jenkinsApi
    }

    GitApi initGitApi() {
        if (!gitApi) {
            assert gitUrl != null
            this.gitApi = new GitApi(gitUrl: gitUrl)
            if (this.branchNameRegex){
                this.gitApi.branchNameFilter = ~this.branchNameRegex
            }
        }

        return this.gitApi
    }
}
