package com.entagen.jenkins

import java.util.regex.Pattern

class GitApi {
    String gitUrl
    Pattern branchNameFilter = null

    public List<String> _getBranchNames(String repos) {
        String command = "git ls-remote --heads " + repos
        List<String> branchNames = []

        eachResultLine(command) { String line ->
            String branchNameRegex = "^.*\trefs/heads/(.*)\$"
            String branchName = line.find(branchNameRegex) { full, branchName -> branchName }
            Boolean selected = passesFilter(branchName)
            println "\t" + (selected ? "* " : "  ") + "$line"
            // lines are in the format of: <SHA>\trefs/heads/BRANCH_NAME
            // ex: b9c209a2bf1c159168bf6bc2dfa9540da7e8c4a26\trefs/heads/master
            if (selected) branchNames << branchName
        }

        return branchNames
    }

    public List<String> getBranchNames() {
        List<String> cb = _getBranchNames("${gitUrl}/internal")
        List<String> prob=_getBranchNames("${gitUrl}/pro")
        prob.removeAll(cb)
        cb.addAll(prob)
        return cb.sort()
    }

    public Boolean passesFilter(String branchName) {
        if (!branchName) return false
        if (branchName.endsWith("master") && branchName.length() > 6) return false
        if (!branchNameFilter) return true
        return branchName ==~ branchNameFilter
    }

    // assumes all commands are "safe", if we implement any destructive git commands, we'd want to separate those out for a dry-run
    public void eachResultLine(String command, Closure closure) {
        println "executing command: $command"
        def process = command.execute()
        def inputStream = process.getInputStream()
        def gitOutput = ""

        while(true) {
          int readByte = inputStream.read()
          if (readByte == -1) break // EOF
          byte[] bytes = new byte[1]
          bytes[0] = readByte
          gitOutput = gitOutput.concat(new String(bytes))
        }
        process.waitFor()

        if (process.exitValue() == 0) {
            gitOutput.eachLine { String line ->
               closure(line)
          }
        } else {
            String errorText = process.errorStream.text?.trim()
            println "error executing command: $command"
            println errorText
            throw new Exception("Error executing command: $command -> $errorText")
        }
    }

}
