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

        // Using the sh step to execute the command
        // This will capture the standard output of the command
        def output = sh(script: command, returnStdout: true).trim()

        // Check for errors or non-zero exit status if needed
        // Jenkins will mark the build as failed if the command exits with non-zero status

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
