//file:noinspection GrMethodMayBeStatic
package com.meshiq.jenkins

import java.lang.ProcessBuilder

class CodeArtifact {

    private final String awsDomain
    private final String awsDomainOwner
    private final String awsRegion

    CodeArtifact(String awsDomain, String awsDomainOwner, String awsRegion) {
        this.awsDomain = awsDomain
        this.awsDomainOwner = awsDomainOwner
        this.awsRegion = awsRegion
    }

    String generateToken() {
        String command = "aws codeartifact get-authorization-token --domain $awsDomain --domain-owner $awsDomainOwner --region $awsRegion --query authorizationToken --output text"
        return executeCommand(command)
    }

    boolean hasPackage(String repository, String packageGroup, String packageName, String packageVersion) {
        validateParameters(repository, packageGroup, packageName, packageVersion)
        String command = generateCall('list-package-versions', [
                '--domain', awsDomain,
                '--domain-owner', awsDomainOwner,
                '--repository', repository,
                '--namespace', packageGroup,
                '--package', packageName,
                '--query', "versions[?version=='$packageVersion'].version".toString(),
                '--format', 'maven',
                '--output', 'text'
        ])

        String output = executeCommand(command)
        return output && output.trim() == packageVersion
    }

    void deletePackage(String repository, String packageGroup, String packageName, String packageVersion) {
        validateParameters(repository, packageGroup, packageName, packageVersion)
        String command = generateCall('delete-package-versions', [
                '--domain', awsDomain,
                '--domain-owner', awsDomainOwner,
                '--repository', repository,
                '--format', 'maven',
                '--namespace', packageGroup,
                '--package', packageName,
                '--versions', packageVersion
        ])

        executeCommand(command)
    }

    String executeCommand(String command) {
        println "executing: $command"
        def processBuilder = new ProcessBuilder(command.split(' '))
        processBuilder.redirectErrorStream(true)
        Process process = processBuilder.start()
        def output = process.text.trim()
        process.waitFor()

        if (process.exitValue() != 0) {
            throw new Exception("Command execution failed with exit code: ${process.exitValue()}\nOutput: $output")
        }

        return output
    }

    private String generateCall(String awsCommand, List<String> options) {
        return "aws codeartifact $awsCommand " + options.join(' ')
    }

    private void validateParameters(String... params) {
        params.each { param ->
            if (param == null || param.isEmpty()) {
                throw new IllegalArgumentException("One or more required parameters are null or empty")
            }
        }
    }
}
