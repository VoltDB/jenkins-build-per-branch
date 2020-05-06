package com.entagen.jenkins

import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.*
import org.apache.http.conn.HttpHostConnectException
import org.apache.http.client.HttpResponseException
import org.apache.http.HttpStatus
import org.apache.http.HttpRequestInterceptor
import org.apache.http.protocol.HttpContext
import org.apache.http.HttpRequest
import com.offbytwo.jenkins.JenkinsServer
import com.offbytwo.jenkins.model.*
import java.util.regex.Pattern
import com.google.common.base.Optional

class JenkinsApi2 {
    String jenkinsServerUrl
    RESTClient restClient
    HttpRequestInterceptor requestInterceptor
    boolean findCrumb = true
    def crumbInfo

    JenkinsServer jenkins
    Map<String, Job> jobs = null

    public JenkinsApi2(String jenkinsServerUrl, String user, String passwd) {
        if (!jenkinsServerUrl.endsWith("/")) jenkinsServerUrl += "/"
        this.jenkinsServerUrl = jenkinsServerUrl
        //this.restClient = new RESTClient(jenkinsServerUrl)
        this.jenkins = new JenkinsServer(new URI(jenkinsServerUrl), user, passwd)
    }

    List<String> getJobNames(String prefix = null) {
        if ( jobs == null ) {
            jobs = jenkins.getJobs()
        }
        return new ArrayList(jobs.keySet())
    }

    String getJobConfig(String jobName) {
        // model information is here:  https://github.com/jenkinsci/java-client-api/tree/master/jenkins-client/src/main/java/com/offbytwo/jenkins/model
        return jenkins.getJobXml(jobName);

    }

    void cloneJobForBranch(ConcreteJob missingJob, List<TemplateJob> templateJobs) {
        String missingJobConfig = configForMissingJob(missingJob, templateJobs)
        TemplateJob templateJob = missingJob.templateJob

        //Copy job with jenkins copy job api, this will make sure jenkins plugins get the call to make a copy if needed (promoted builds plugin needs this)
        //println "Creating $missingJobConfig $missingJob.jobName $templateJob.jobName"
        println "Creating $missingJob.jobName from $templateJob.jobName"

        jenkins.createJob(missingJob.jobName,missingJobConfig,true)
        //post('createItem', missingJobConfig, [name: missingJob.jobName, mode: 'copy', from: templateJob.jobName], ContentType.XML)

        //post('job/' + missingJob.jobName + "/config.xml", missingJobConfig, [:], ContentType.XML)
        //Forced disable enable to work around Jenkins' automatic disabling of clones jobs
        //But only if the original job was enabled
        //post('job/' + missingJob.jobName + '/disable')
        //if (!missingJobConfig.contains("<disabled>true</disabled>")) {
        //    post('job/' + missingJob.jobName + '/enable')
        //}
    }

    void startJob(ConcreteJob job) {
        println "Starting job ${job.jobName}."

        job = jenkins.getJob(job.jobName)
        job.build()
    }

    String configForMissingJob(ConcreteJob missingJob, List<TemplateJob> templateJobs) {
        TemplateJob templateJob = missingJob.templateJob
        String config = getJobConfig(templateJob.jobName)

        def ignoreTags = ["assignedNode"]

        // should work if there's a remote ("origin/master") or no remote (just "master")
        config = config.replaceAll("(\\p{Alnum}*[>/])(${templateJob.templateBranchName})<") { fullMatch, prefix, branchName ->
            // jenkins job configs may have certain fields whose values should not be replaced, the most common being <assignedNode>
            // which is used to assign a job to a specific node (potentially "master") and the "master" branch
            if (ignoreTags.find { it + ">" == prefix}) {
                return fullMatch
            } else {
                return "$prefix${missingJob.branchName}<"
            }
        }

        // this is in case there are other down-stream jobs that this job calls, we want to be sure we're replacing their names as well
        templateJobs.each {
            config = config.replaceAll(it.jobName, it.jobNameForBranch(missingJob.branchName))
        }

        return config
    }

    void deleteJob(String jobName) {
        println "deleting job $jobName"
        jenkins.deleteJob(jobName,true)
    }

    String getViewXml(String view) {

    }

    public View findView(String viewName) {
        return jenkins.getView(viewName);
    }

    void createViewForBranch(BranchView branchView, String nestedWithinView = null) {
        String viewName = branchView.viewName
        // refresh the jobs list
        this.jobs = jenkins.getJobs()

        Map body = [name: viewName, mode: 'hudson.model.ListView', Submit: 'OK', json: '{"name": "' + viewName + '", "mode": "hudson.model.ListView"}']
        println "creating view - viewName:${viewName}, nestedView:${nestedWithinView}"
        //post(buildViewPath("createView", nestedWithinView), body)
        View view = findView(viewName)

        /*
        if (nestedWithinView != null ) {
            for (v in jenkins.getView(nestedWithinView).getViews() ) {
                if (viewName.equals(v.getName()) ) {
                    view = v
                    break;
                }
            }
            // maybe it's at the top level, not nested like we thought
            view = jenkins.getView(viewName)
        }  else {
            view = jenkins.getView(viewName)
        }
        */

        List<String> viewJobs = new ArrayList<String>()
        String pattern = branchView.templateJobPrefix+".*"+branchView.safeBranchName
        String jobxmlstring= ""
        for ( String jobName in getJobNames() ) {
            if ( jobName ==~ /${pattern}/ ) {
                Job job = this.jobs.get(jobName)
                println "adding job to view: "+job.getName()
                viewJobs.add(job)
                jobxmlstring += "<string>"+job.getName()+"</string>"

            }
        }


        /*
        if (view == null)

            jenkins.createView(viewName,VIEW_COLUMNS_XML,true)
            view = jenkins.getView(viewName)
        }
        */

        /* this works, but we aren't ready for it yet
        * the jobs need to be created in the folder as well.
        jenkins.createFolder(viewName,true)
        Job job = jenkins.getJobs().get(viewName);
        Optional<FolderJob> maybeFolder = jenkins.getFolderJob(job);
        FolderJob j = maybeFolder.get();
        String viewxml = VIEW_COLUMNS_XML.replace("REPLACEME",jobxmlstring)
        jenkins.createView(j,viewName,viewxml,true)
        */

        String viewxml = VIEW_COLUMNS_XML.replace("REPLACEME",jobxmlstring)
        jenkins.createView(viewName,viewxml,true)


        // DOESN"T WORK
        if (nestedWithinView != null ) {
            View parentView = jenkins.getView(nestedWithinView);
            List views = parentView.getViews()
            views.add(view)
            parentView.setViews(views)
        }

        //println "viewjobs has "+viewJobs.size()+" jobs"
        //view.setJobs(viewJobs)
    }

    List<String> getViewNames(String nestedWithinView = null) {
        println "getting views - nestedWithinView:${nestedWithinView}"
        List viewNames = new ArrayList<String>()
        if (nestedWithinView != null ) {
            jenkins.getView(nestedWithinView).getViews().each { viewNames.add(it.getName()) }
        }
        // add the top level views too, this is a Map<Sting,View>
        jenkins.getViews().keySet().each { viewNames.add(it) }

        return viewNames
    }

    void deleteView(String viewName, String nestedWithinView = null) {
        println "deleting view - viewName:${viewName}, nestedView:${nestedWithinView}"
        //Also, delete any remaining jobs in the view
        //String path = "view/${nestedWithinView}/view/${viewName}/api/json"
        //def response = get(path: path)
        def view = findView(viewName)
        def jobs = view.getJobs()
        //List<String> jobNames = response.data?.jobs?.name

        println "found view "+view.getName()+" with num jobs:"+jobs.size()
        jobs.each { deleteJob(it.getName()) }
        //post(buildViewPath("doDelete", nestedWithinView, viewName))
        if (viewName.startsWith('branch-')) {
            def branch = viewName.replaceAll("branch-","")
            println "cleaning up " + branch
            def cmd = "sudo /var/lib/jenkins/cleanup-deleted-branch.sh "+ branch
            def proc = cmd.execute()
            def b = new StringBuffer()
            proc.consumeProcessErrorStream(b)
            println proc.text
            println b.toString()
        }
        //this doesn't work
        //jenkins.deleteView(viewName,true)

        // this will generate an error on stdout, but it deletes the view
        String script = "Jenkins.instance.getView(\""+viewName+"\").doDoDelete(null,null)"
        String res = jenkins.runScript(script, true)
        //println("deleteview res:"+res)
    }

    protected String buildViewPath(String pathSuffix, String... nestedViews) {
        List elems = nestedViews.findAll { it != null }
        String viewPrefix = elems.collect { "view/${it}" }.join('/')

        if (viewPrefix) return "$viewPrefix/$pathSuffix"

        return pathSuffix
    }


    static final String VIEW_COLUMNS_JSON = '''
"columns":[
      {
         "$class":"hudson.views.StatusColumn",
         "stapler-class":"hudson.views.StatusColumn",
         "kind":"hudson.views.StatusColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.WeatherColumn",
         "stapler-class":"hudson.views.WeatherColumn",
         "kind":"hudson.views.WeatherColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.JobColumn",
         "stapler-class":"hudson.views.JobColumn",
         "kind":"hudson.views.JobColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.LastSuccessColumn",
         "stapler-class":"hudson.views.LastSuccessColumn",
         "kind":"hudson.views.LastSuccessColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.LastFailureColumn",
         "stapler-class":"hudson.views.LastFailureColumn",
         "kind":"hudson.views.LastFailureColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.LastDurationColumn",
         "stapler-class":"hudson.views.LastDurationColumn",
         "kind":"hudson.views.LastDurationColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.BuildButtonColumn",
         "stapler-class":"hudson.views.BuildButtonColumn",
         "kind":"hudson.views.BuildButtonColumn$DescriptorImpl"
      }
   ]
'''

    static final String VIEW_COLUMNS_XML = '''
       <listView>
          <owner class="hudson" reference="../../.."/>
          <name>view-template</name>
          <filterExecutors>false</filterExecutors>
          <filterQueue>false</filterQueue>
          <properties class="hudson.model.View$PropertyList"/>
          <jobNames>
            REPLACEME
          </jobNames>
          <jobFilters/>
          <columns>
            <hudson.views.StatusColumn/>
            <hudson.views.WeatherColumn/>
            <hudson.views.JobColumn/>
            <hudson.views.LastSuccessColumn/>
            <hudson.views.LastFailureColumn/>
            <hudson.views.LastDurationColumn/>
            <hudson.views.BuildButtonColumn/>
          </columns>
          <recurse>false</recurse>
        </listView>
'''
}
