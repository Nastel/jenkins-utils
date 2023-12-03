//file:noinspection GrMethodMayBeStatic
package com.meshiq.jenkins

import com.cloudbees.groovy.cps.NonCPS

import java.lang.ProcessBuilder
import java.util.logging.Logger

class CodeArtifact {

    private static final Logger log = Logger.getLogger(this.class.name)

    private final String awsDomain
    private final String awsDomainOwner
    private final String awsRegion
    private final String credentialsId

    CodeArtifact(String awsDomain, String awsDomainOwner, String awsRegion, credentialsId) {
        this.awsDomain = awsDomain
        this.awsDomainOwner = awsDomainOwner
        this.awsRegion = awsRegion
        this.credentialsId = credentialsId
    }

    @NonCPS
    String generateToken() {
        String command = "aws codeartifact get-authorization-token --domain $awsDomain --domain-owner $awsDomainOwner --region $awsRegion --query authorizationToken --output text"
        return executeCommand(command)
    }

    @NonCPS
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

    @NonCPS
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

    @NonCPS
    String executeCommand(String command) {
        log.info("Executing command: $command")


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

    @NonCPS
    private String generateCall(String awsCommand, List<String> options) {
        return "aws codeartifact $awsCommand " + options.join(' ')
    }

    @NonCPS
    private void validateParameters(String... params) {
        params.each { param ->
            if (param == null || param.isEmpty()) {
                throw new IllegalArgumentException("One or more required parameters are null or empty")
            }
        }
    }
}
