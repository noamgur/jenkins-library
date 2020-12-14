import com.sap.piper.CredentialType
import com.sap.piper.DebugReport
import groovy.json.JsonSlurper
import hudson.AbortException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.RuleChain
import util.*

import static org.hamcrest.Matchers.*
import static org.junit.Assert.assertThat

class PiperExecuteBinTest extends BasePiperTest {
    private ExpectedException exception = ExpectedException.none()

    private JenkinsCredentialsRule credentialsRule = new JenkinsCredentialsRule(this)
    private JenkinsShellCallRule shellCallRule = new JenkinsShellCallRule(this)
    private JenkinsStepRule stepRule = new JenkinsStepRule(this)
    private JenkinsWriteFileRule writeFileRule = new JenkinsWriteFileRule(this)
    private JenkinsFileExistsRule fileExistsRule = new JenkinsFileExistsRule(this, [])
    private JenkinsDockerExecuteRule dockerExecuteRule = new JenkinsDockerExecuteRule(this)

    private List withEnvArgs = []
    private List credentials = []
    private List artifacts = []
    private int stringCredentialClassCalled = 0

    @Rule
    public RuleChain rules = Rules
        .getCommonRules(this)
        .around(exception)
        .around(new JenkinsReadYamlRule(this))
        .around(credentialsRule)
        .around(shellCallRule)
        .around(stepRule)
        .around(writeFileRule)
        .around(fileExistsRule)
        .around(dockerExecuteRule)

    @Before
    void init() {
        credentials = []
        stringCredentialClassCalled = 0

        // Clear DebugReport to avoid left-overs from another UnitTest
        DebugReport.instance.failedBuild = [:]

        helper.registerAllowedMethod("withEnv", [List.class, Closure.class], {arguments, closure ->
            arguments.each {arg ->
                withEnvArgs.add(arg.toString())
            }
            return closure()
        })

        helper.registerAllowedMethod('fileExists', [Map.class], {m ->
            if (m.file == 'noDetailsStep_errorDetails.json') {
                return false
            }
            return true
        })
        helper.registerAllowedMethod("readJSON", [Map], { m ->
            if(m.file == 'testStep_reports.json')
                return [[target: "1234.pdf", mandatory: true]]
            if(m.file == 'testStep_links.json')
                return []
            if(m.file == 'testStepCategory_errorDetails.json')
                return [message: 'detailed error', category: 'testCategory']
            if(m.file == 'testStep_errorDetails.json')
                return [message: 'detailed error']
            if(m.text != null)
                return new JsonSlurper().parseText(m.text)
        })

        helper.registerAllowedMethod('libraryResource', [String.class], {s ->
            if (s == 'metadata/test.yaml') {
                return '''metadata:
  name: testStep
'''
            } else {
                return '''general:
  failOnError: true
'''
            }
        })

        helper.registerAllowedMethod('file', [Map], { m -> return m })
        helper.registerAllowedMethod('string', [Map], { m ->
            stringCredentialClassCalled += 1
            return m
        })
        helper.registerAllowedMethod('usernamePassword', [Map], { m -> return m })
        helper.registerAllowedMethod('withCredentials', [List, Closure], { l, c ->
            l.each {m ->
                credentials.add(m)
                if (m.credentialsId == 'credFile') {
                    binding.setProperty('PIPER_credFile', 'credFileContent')
                } else if (m.credentialsId == 'credToken') {
                    binding.setProperty('PIPER_credToken','credTokenContent')
                } else if (m.credentialsId == 'credUsernamePassword') {
                    binding.setProperty('PIPER_user', 'userId')
                    binding.setProperty('PIPER_password', '********')
                }
            }
            try {
                c()
            } finally {
                binding.setProperty('PIPER_credFile', null)
                binding.setProperty('PIPER_credToken', null)
                binding.setProperty('PIPER_user', null)
                binding.setProperty('PIPER_password', null)
            }
        })

        helper.registerAllowedMethod('archiveArtifacts', [Map.class], {m ->
            artifacts.add(m)
            return null
        })

        helper.registerAllowedMethod('findFiles', [Map.class], {m -> return null})
    }

    @Test
    void testPiperExecuteBinDefault() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{"fileCredentialsId":"credFile", "tokenCredentialsId":"credToken", "credentialsId":"credUsernamePassword", "dockerImage":"my.Registry/my/image:latest"}')

        List stepCredentials = [
            [type: 'file', id: 'fileCredentialsId', env: ['PIPER_credFile']],
            [type: 'token', id: 'tokenCredentialsId', env: ['PIPER_credToken']],
            [type: 'usernamePassword', id: 'credentialsId', env: ['PIPER_user', 'PIPER_password']],
        ]
        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                testParam: "This is test content",
                script: nullScript
            ],
            'testStep',
            'metadata/test.yaml',
            stepCredentials
        )
        // asserts
        assertThat(writeFileRule.files['.pipeline/tmp/metadata/test.yaml'], containsString('name: testStep'))
        assertThat(withEnvArgs[0], allOf(startsWith('PIPER_parametersJSON'), containsString('"testParam":"This is test content"')))
        assertThat(shellCallRule.shell[1], is('./piper testStep'))
        assertThat(credentials.size(), is(3))
        assertThat(credentials[0], allOf(hasEntry('credentialsId', 'credFile'), hasEntry('variable', 'PIPER_credFile')))
        assertThat(credentials[1], allOf(hasEntry('credentialsId', 'credToken'), hasEntry('variable', 'PIPER_credToken')))
        assertThat(credentials[2], allOf(hasEntry('credentialsId', 'credUsernamePassword'), hasEntry('usernameVariable', 'PIPER_user') , hasEntry('passwordVariable', 'PIPER_password')))

        assertThat(dockerExecuteRule.dockerParams.dockerImage, is('my.Registry/my/image:latest'))
        assertThat(dockerExecuteRule.dockerParams.stashContent, is([]))

        assertThat(artifacts[0], allOf(hasEntry('artifacts', '1234.pdf'), hasEntry('allowEmptyArchive', false)))
    }

    @Test
    void testPiperExecuteBinDontResolveCredentialsAndNoCredId() {

        // In case we have a credential entry without Id we drop that silenty.
        // Maybe we should revisit that and fail in this case.

        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{"dockerImage":"my.Registry/my/image:latest"}')

        List stepCredentials = [
            [type: 'token', env: ['PIPER_credTokenNoResolve'], resolveCredentialsId: false],
        ]

        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                testParam: "This is test content",
                script: nullScript
            ],
            'testStep',
            'metadata/test.yaml',
            stepCredentials
        )
        assertThat(credentials.size(), is(0))
    }

    @Test
    void testPiperExecuteBinOneCredentialNotSetInConfig() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{"fileCredentialsId":"credFile", "tokenCredentialsId":"credToken", "dockerImage":"my.Registry/my/image:latest"}')

        List stepCredentials = [
            [type: 'file', id: 'fileCredentialsId', env: ['PIPER_credFile']],
            [type: 'token', id: 'tokenCredentialsId', env: ['PIPER_credToken']],
            // for the entry below we don't have a config lookup.
            [type: 'token', id: 'tokenCredentialsIdNoResolve', env: ['PIPER_credTokenNoResolve'], resolveCredentialsId: false],
            [type: 'token', id: 'tokenCredentialsIdNotContainedInConfig', env: ['PIPER_credToken_doesNotMatter']],
            [type: 'usernamePassword', id: 'credentialsId', env: ['PIPER_user', 'PIPER_password']],
        ]
        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                testParam: "This is test content",
                script: nullScript
            ],
            'testStep',
            'metadata/test.yaml',
            stepCredentials
        )
        // asserts
        assertThat(credentials.size(), is(3))
        assertThat(credentials[0], allOf(hasEntry('credentialsId', 'credFile'), hasEntry('variable', 'PIPER_credFile')))
        assertThat(credentials[1], allOf(hasEntry('credentialsId', 'credToken'), hasEntry('variable', 'PIPER_credToken')))
        assertThat(credentials[2], allOf(hasEntry('credentialsId', 'tokenCredentialsIdNoResolve'), hasEntry('variable', 'PIPER_credTokenNoResolve')))
    }

    @Test
    void testPiperExecuteBinCredentialsSetAsEnum() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{"sshCredentialsId":"sshKey", "fileCredentialsId":"cred_file", "tokenCredentialsId":"cred_token", "secretTextCredentialsId":"cred_secretText", "credentialsId":"credUsernamePassword", "dockerImage":"my.Registry/my/image:latest"}')

        List sshKey = []
        helper.registerAllowedMethod("sshagent", [List, Closure], {s, c ->
            sshKey = s
            c()
        })

        List stepCredentials = [[type: CredentialType.USERNAME_PASSWORD, id: 'credentialsId', env: ['PIPER_user', 'PIPER_password']]]
        for (type in CredentialType.values()) {
            if (type != CredentialType.USERNAME_PASSWORD) {
                stepCredentials.add([type: type, id: "${type}CredentialsId", env: ["PIPER_cred_${type}"]])
            }
        }

        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                testParam: "This is test content",
                script: nullScript
            ],
            'testStep',
            'metadata/test.yaml',
            stepCredentials
        )
        // asserts
        assertThat(credentials.size(), is(4))
        assertThat(sshKey, is(['sshKey']))
        assertThat(credentials[0], allOf(hasEntry('credentialsId', 'credUsernamePassword'), hasEntry('usernameVariable', 'PIPER_user') , hasEntry('passwordVariable', 'PIPER_password')))
        int index = 1
        for (type in CredentialType.values()) {
            if (type != CredentialType.USERNAME_PASSWORD && type != CredentialType.SSH) {
                def credId = "cred_${type}".toString() // toString() to avoid failure due to comparison to GString
                assertThat(credentials[index++], allOf(hasEntry('credentialsId', credId), hasEntry('variable', "PIPER_cred_${type}")))
            }
        }
        assertThat(stringCredentialClassCalled, is(2))
        assertThat(sshKey, is(['sshKey']))

    }

    @Test
    void testPiperExecuteBinNoDockerNoCredentials() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{}')

        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                testParam: "This is test content",
                script: nullScript
            ],
            'testStep',
            'metadata/test.yaml',
            []
        )

        assertThat(writeFileRule.files['.pipeline/tmp/metadata/test.yaml'], containsString('name: testStep'))
        assertThat(withEnvArgs[0], allOf(startsWith('PIPER_parametersJSON'), containsString('"testParam":"This is test content"')))
        assertThat(shellCallRule.shell[1], is('./piper testStep'))
        assertThat(credentials.size(), is(0))

        assertThat(dockerExecuteRule.dockerParams.size(), is(0))

        assertThat(artifacts[0], allOf(hasEntry('artifacts', '1234.pdf'), hasEntry('allowEmptyArchive', false)))

    }

    @Test
    void testPiperExecuteBinNoReportFound() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{}')
        helper.registerAllowedMethod('fileExists', [Map], {
            return false
        })

        exception.expect(AbortException)
        exception.expectMessage("Expected to find testStep_reports.json in workspace but it is not there")

        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                testParam: "This is test content",
                script: nullScript
            ],
            'testStep',
            'metadata/test.yaml',
            [],
            true,
            false,
            false
        )
    }

    @Test
    void testErrorWithCategory() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{}')
        helper.registerAllowedMethod('sh', [String.class], {s -> throw new AbortException('exit code 1')})

        exception.expect(AbortException)
        exception.expectMessage("[testStepCategory] Step execution failed (category: testCategory). Error: detailed error")

        try {
            stepRule.step.piperExecuteBin(
                [
                    juStabUtils: utils,
                    jenkinsUtilsStub: jenkinsUtils,
                    testParam: "This is test content",
                    script: nullScript
                ],
                'testStepCategory',
                'metadata/test.yaml',
                []
            )
        } catch (ex) {
            assertThat(DebugReport.instance.failedBuild.category, is('testCategory'))
            throw ex
        }

    }

    @Test
    void testErrorWithoutCategory() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{}')
        helper.registerAllowedMethod('sh', [String.class], {s -> throw new AbortException('exit code 1')})

        exception.expect(AbortException)
        exception.expectMessage("[testStep] Step execution failed. Error: detailed error")

        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                testParam: "This is test content",
                script: nullScript
            ],
            'testStep',
            'metadata/test.yaml',
            []
        )
    }

    @Test
    void testErrorNoDetails() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{}')
        helper.registerAllowedMethod('sh', [String.class], {s -> throw new AbortException('exit code 1')})

        exception.expect(AbortException)
        exception.expectMessage("[noDetailsStep] Step execution failed. Error: hudson.AbortException: exit code 1, please see log file for more details.")

        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                testParam: "This is test content",
                script: nullScript
            ],
            'noDetailsStep',
            'metadata/test.yaml',
            []
        )
    }

    @Test
    void testRespectPipelineResilienceSetting() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{}')
        helper.registerAllowedMethod('sh', [String.class], {s -> throw new AbortException('exit code 1')})

        def unstableCalled
        helper.registerAllowedMethod('unstable', [String.class], {s -> unstableCalled = true})

        try {
            nullScript.commonPipelineEnvironment.configuration.steps = [handlePipelineStepErrors: [failOnError: false]]

            stepRule.step.piperExecuteBin(
                [
                    juStabUtils: utils,
                    jenkinsUtilsStub: jenkinsUtils,
                    testParam: "This is test content",
                    script: nullScript
                ],
                'noDetailsStep',
                'metadata/test.yaml',
                []
            )
        } finally {
            //clean up
            nullScript.commonPipelineEnvironment.configuration.steps = [handlePipelineStepErrors: [failOnError: true]]
        }
        assertThat(unstableCalled, is(true))

    }

    @Test
    void testProperStashHandling() {
        shellCallRule.setReturnValue('./piper getConfig --contextConfig --stepMetadata \'.pipeline/tmp/metadata/test.yaml\'', '{"dockerImage":"test","stashContent":["buildDescriptor"]}')

        stepRule.step.piperExecuteBin(
            [
                juStabUtils: utils,
                jenkinsUtilsStub: jenkinsUtils,
                script: nullScript
            ],
            'testStep',
            'metadata/test.yaml',
            []
        )

        assertThat(dockerExecuteRule.dockerParams.stashContent, is(["buildDescriptor", "pipelineConfigAndTests", "piper-bin"]))
    }
}
