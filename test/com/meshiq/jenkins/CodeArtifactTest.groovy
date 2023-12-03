package com.meshiq.jenkins

import spock.lang.Specification

class CodeArtifactTest extends Specification {

    def "test generateToken"() {
        given:
        def codeArtifact = new CodeArtifact('aws-domain', 'aws-domain-owner', 'aws-region', 'id')
        codeArtifact.metaClass.executeCommand = { String command ->
            assert command.contains('get-authorization-token')
            return 'token-123'
        }

        when:
        def token = codeArtifact.generateToken()

        then:
        token == 'token-123'
    }

    def "test hasPackage"() {
        given:
        def codeArtifact = new CodeArtifact('aws-domain', 'aws-domain-owner', 'aws-region', 'id')
        codeArtifact.metaClass.executeCommand = { String command ->
            command.contains('list-package-versions') && packageVersion == 'existing-version' ? 'existing-version' : ''
        }

        expect:
        codeArtifact.hasPackage('repository', 'group', 'name', packageVersion) == expectedResult

        where:
        packageVersion  | expectedResult
        'existing-version' | true
        'nonexistent-version' | false
    }

    def "test deletePackage"() {
        given:
        def codeArtifact = new CodeArtifact('aws-domain', 'aws-domain-owner', 'aws-region', 'id')
        codeArtifact.metaClass.executeCommand = { String command ->
            assert command.contains('delete-package-versions')
            assert command.contains('deletable-version')
            null // Assuming command execution is successful
        }

        when:
        codeArtifact.deletePackage('repository', 'group', 'name', 'deletable-version')

        then:
        noExceptionThrown()
    }
}
