import java.io.File
import java.nio.file.Files
import java.util.List
import java.util.ArrayList

@Grab(group='com.fasterxml.jackson.core', module='jackson-databind', version='2.11.1')
@Grab(group='software.amazon.awssdk', module='apache-client', version='2.13.63')
import software.amazon.awssdk.http.apache.ApacheHttpClient
@Grab(group='software.amazon.awssdk', module='lambda', version='2.13.63')
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest
import software.amazon.awssdk.services.lambda.model.DeleteProvisionedConcurrencyConfigRequest
import software.amazon.awssdk.services.lambda.model.FunctionCode
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration
import software.amazon.awssdk.services.lambda.model.GetFunctionConfigurationRequest
import software.amazon.awssdk.services.lambda.model.GetFunctionConcurrencyRequest
import software.amazon.awssdk.services.lambda.model.ListFunctionsRequest
import software.amazon.awssdk.services.lambda.model.ListLayerVersionsRequest
import software.amazon.awssdk.services.lambda.model.ListVersionsByFunctionRequest
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsRequest
import software.amazon.awssdk.services.lambda.model.PutProvisionedConcurrencyConfigRequest
import software.amazon.awssdk.services.lambda.model.UpdateFunctionCodeRequest
import software.amazon.awssdk.services.lambda.model.UpdateFunctionConfigurationRequest

//System.setProperty("aws.accessKeyId", "XXX")
//System.setProperty("aws.secretAccessKey", "XXX")

// TODO:
//  * Check hash of the latest built JAR and compare it to version already deployed. if it is same, skip updating.
//     Note: This will only work if building JARs are deterministic which I don't think they are. Could also compare
//     the version String of the newly built JAR and deployed one.
LambdaClient lambdaClient = LambdaClient.builder()
        .httpClient(ApacheHttpClient.builder().build())
        .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
        //.credentialsProvider(SystemPropertyCredentialsProvider.create())
        .region(Region.US_WEST_2).build()

// Get the latest version of the custom Java runtime.
def listLayerVersionRequest = ListLayerVersionsRequest.builder().layerName("Java-11").build()
def layerVersionResponse = lambdaClient.listLayerVersions(listLayerVersionRequest)
def latestVersionArn = layerVersionResponse.layerVersions().get(0).layerVersionArn()

System.out.println("Latest version of custom Java runtime: " + latestVersionArn)

def jarFile = new File(project.build.directory + "/${project.name}-${project.version}-lambda_deployment_package_assembly.zip")

// See if this lambda function already exists and if not - create it.
def listFunctionsResponse = lambdaClient.listFunctions(ListFunctionsRequest.builder().build())
System.out.println("Using  \"${project.name}\" as lambda function name.")
String projectName = "${project.name}" // This needs to be a String (not GString) for equals comparison

if (!listFunctionsResponse.functions().stream().anyMatch(f -> f.functionName().equals(projectName))) {
    System.out.println("Could not find Lambda with function name \"${project.name}\" so creating one...")
    // Create the lambda (with the code just compiled).
    def createFunctionRequest = CreateFunctionRequest.builder()
        .code(FunctionCode.builder()
            .zipFile(SdkBytes.fromByteArray(Files.readAllBytes(jarFile.toPath()))).build())
        .description("${project.name} Lambda v${project.version}")
        .functionName("${project.name}")
        .handler(project.properties['aws.lambda.handler'])
        .layers(latestVersionArn)
        .memorySize(512)
        .publish(true)
        .role("arn:aws:iam::1234:role/service-role")
        .runtime("provided")
        .build()
    def createFunctionResponse = lambdaClient.createFunction(createFunctionRequest)
    System.out.println("createFunctionResponse: " + createFunctionResponse)
} else {
    // Update the lambda to use the latest code just compiled.
    def updateFunctionCodeRequest = UpdateFunctionCodeRequest.builder().functionName("${project.name}")
            .zipFile(SdkBytes.fromByteArray(Files.readAllBytes(jarFile.toPath())))
            //.withS3Bucket("myBucket")
            //.withS3Key("myKey")
            //.withS3ObjectVersion("1")
            .publish(true).build()
    def updateFunctionCodeResponse = lambdaClient.updateFunctionCode(updateFunctionCodeRequest)
    def latestVersion = updateFunctionCodeResponse.version()
    def latestVersionInt = latestVersion as int

    def provisionedConfigs = lambdaClient.listProvisionedConcurrencyConfigs(
            ListProvisionedConcurrencyConfigsRequest.builder().functionName("${project.name}").build())
            .provisionedConcurrencyConfigs()

    if (!provisionedConfigs.isEmpty()) {
        // Note: If a new lambda runtime was just uploaded it may not yet be provisioned so we need to also check
        // the requested concurrent executions.
        def requestedConcurrentExecutions = provisionedConfigs.get(0).requestedProvisionedConcurrentExecutions()
        def reservedConcurrentExecutions = provisionedConfigs.get(0).allocatedProvisionedConcurrentExecutions()

        /*
        def functionConcurrencyResponse = lambdaClient.getFunctionConcurrency(GetFunctionConcurrencyRequest.builder()
                .functionName("${project.name}").build())
        System.out.println("functionConcurrencyResponse: " + functionConcurrencyResponse)

        def reservedConcurrentExecutions = functionConcurrencyResponse.reservedConcurrentExecutions()
        */
        System.out.println("Requested concurrent executions: " + requestedConcurrentExecutions)
        System.out.println("Reserved concurrent executions: " + reservedConcurrentExecutions)

        if (reservedConcurrentExecutions > 0 || requestedConcurrentExecutions > 0) {
            // Deallocate reserved concurrency from previous version, allocate to new version
            System.out.println("Version " + (latestVersionInt - 1) + " had provisioned concurrency - so switching " +
                    "it to new version: " + latestVersion)
            lambdaClient.deleteProvisionedConcurrencyConfig(DeleteProvisionedConcurrencyConfigRequest.builder()
                    .functionName("${project.name}")
                    .qualifier((latestVersionInt - 1).toString()).build())
            lambdaClient.putProvisionedConcurrencyConfig(PutProvisionedConcurrencyConfigRequest.builder()
                    .functionName("${project.name}")
                    .qualifier(latestVersion)
                    .provisionedConcurrentExecutions(1).build())
        }
    }

    System.out.println("updateFunctionCodeResponse: " + updateFunctionCodeResponse)

    // Delete the all previous versions up to the last 20 most recent.
    List<FunctionConfiguration> previousVersions = new ArrayList<>()
    def versionsForFunction = lambdaClient.listVersionsByFunction(ListVersionsByFunctionRequest.builder()
            .functionName("${project.name}").build())
    previousVersions.addAll(versionsForFunction.versions())
    def moreVersions = versionsForFunction.nextMarker() != null && !versionsForFunction.nextMarker().isEmpty()
    while (moreVersions) {
        versionsForFunction = lambdaClient.listVersionsByFunction(ListVersionsByFunctionRequest.builder()
                .functionName("${project.name}").marker(versionsForFunction.nextMarker()).build())
        previousVersions.addAll(versionsForFunction.versions())
        moreVersions = versionsForFunction.nextMarker() != null && !versionsForFunction.nextMarker().isEmpty()
    }
    System.out.println("previous versions: " + previousVersions)

    for(FunctionConfiguration funcConfig : previousVersions) {
        if (funcConfig.version().isNumber()) {
            // Skip version aliases like "$LATEST"
            if (funcConfig.version() + 20 < latestVersionInt) {
                System.out.println("Deleting old function version \"" + funcConfig.version() + "\"")
                lambdaClient.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName("${project.name}")
                        .qualifier(funcConfig.version()).build())
            }
        }
    }

    // Update the lambda to use the latest custom runtime version.
    lambdaClient.updateFunctionConfiguration(UpdateFunctionConfigurationRequest.builder()
            .layers(latestVersionArn)
            .functionName("${project.name}").build())
}
