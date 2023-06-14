# PDF Search with PostgreSQL (pgvector) Vector Search: Converting automatically PDFs to Text by Uploading to Blob Storage

## 1. Introduction

Recently, I wrote a blog [How to use the Azure OpenAI Embedding model to find the most relevant documents](https://dev.to/azure/how-to-use-the-azure-openai-embedding-model-to-find-the-most-relevant-documents-42lo). By utilizing this feature, you can easily locate the most pertinent documents. In this article, we will explain how to automatically convert PDF files to text by uploading them to Azure Blob Storage and perform vector searches using the Azure OpenAI Embedding model.

***By using this service, you can effortlessly search for any document, be it internal company documents or various academic papers, as long as they are in PDF format. Simply upload the files to Azure Blob Storage, and the system will automatically make them searchable. When you actually perform a search, ChatGPT will summarize and display the relevant sections for you.***

### 1.1 Overview of the Service

The service introduced in this article processes the data through the following steps:

Please obtain the source code of the application from the "[Source Code is Here](https://github.com/yoshioterada/PostgreSQL-Vector-Search-pgvector--for-PDF-file-on-Blob-Storage-english)".

![Azure-Function-Spring-Embedding-Search](https://live.staticflickr.com/65535/52971732748_1dc14e38f2_b.jpg)

1. Upload a PDF file to Azure Blob Storage
2. Azure Functions' Blob Trigger detects the uploaded file and converts the PDF into text on a per-page basis
3. The converted text is vectorized by calling Azure OpenAI Embedding
4. The vectorized data is saved to Azure PostgreSQL Flexible Server.
5. The user enters a search query.
6. The input query is vectorized by calling Azure OpenAI Embedding.
7. Based on the vectorized data, similar data is retrieved from Azure PostgreSQL Flexible Server.
8. The relevant sections of the highly similar result documents are analyzed by Azure OpenAI ChatGPT and returned in a streaming.

### 1.2. Technologies Used

The service introduced in this article utilizes the following Azure services:

- [Azure Blob Storage](https://learn.microsoft.com/azure/storage/blobs/storage-blobs-introduction)
- [Azure Cosmos DB](https://learn.microsoft.com/azure/cosmos-db/introduction)
- [Azure OpenAI](https://learn.microsoft.com/azure/cognitive-services/openai/overview)
- [Azure Functions](https://learn.microsoft.com/azure/azure-functions/functions-overview?pivots=programming-language-java)
- [Azure PostgreSQL Flexible Server](https://learn.microsoft.com/azure/postgresql/flexible-server/overview)
- [Apache Apache PDFBox](https://pdfbox.apache.org/)

## 2. Setup Environment 

At the moment, there is no Azure CLI command available for creating an Azure OpenAI instance, so you will need to create one through the Azure Portal. Before proceeding with the steps below, please create an Azure OpenAI instance using the Azure Portal's GUI (as of June 13, 2023).

For other Azure resources, you can easily create them using the following commands:

```bash
> create-env-en.sh 
```

> Note :   
> Occasionally, even after enabling the vector search (pgvector) feature in Azure PostgreSQL Flexible Server multiple times, it may not be activated, and the execution of queries using vectors may fail. In such cases, please run the script above again to create a new PostgreSQL Flexible Server instance.

Before executing the commands above, please modify the variables within the script to match your environment.

```text
####################### Setting Environment Variables for Creating Azure Resources #######################
# Configuring the Resource Group and location settings
export RESOURCE_GROUP_NAME=Document-Search-Vector1
export DEPLOY_LOCATION=japaneast

# Settings for Azure PostgreSQL Flexible Server (In my environment, there are construction restrictions, so it is set to eastus)
export POSTGRES_INSTALL_LOCATION=eastus
export POSTGRES_SERVER_NAME=documentsearch1
export POSTGRES_USER_NAME=azureuser
export POSTGRES_USER_PASS='!'$(head -c 12 /dev/urandom | base64 | tr -dc '[:alpha:]'| fold -w 8 | head -n 1)$RANDOM
export POSTGRES_DB_NAME=VECTOR_DB
export POSTGRES_TABLE_NAME=DOCUMENT_SEARCH_VECTOR
export PUBLIC_IP=$(curl ifconfig.io -4)

# Settings for Azure Blob Storage
export BLOB_STORAGE_ACCOUNT_NAME=documentsearch1

# Note: If you change the values below, you will also need to modify the implementation part of the BlobTrigger in Functions.java.
export BLOB_CONTAINER_NAME_FOR_PDF=pdfs

# Settings for Azure Cosmos DB
export COSMOS_DB_ACCOUNT_NAME=odocumentsearchstatus1
export COSMOS_DB_DB_NAME=documentregistrystatus
export COSMOS_DB_CONTAINER_NAME_FOR_STATUS=status

# Obtaining the Azure subscription ID (If you are using the default subscription, you do not need to make the changes below)
export SUBSCRIPTION_ID="$(az account list --query "[?isDefault].id" -o tsv)"
####################### Setting Environment Variables for Creating Azure Resources #######################
```

### 2.1 Setting Environment Variables

After executing the script above, if it is successful, you will see a message like below. Please update the `local.settings.json` file for Azure Functions (BlobUploadDetector) and the `application.properties` file for Spring Boot (PDF-Summarizer), respectively, according to the output content.

```text
####################### Setting Environment Variables for Creating Azure Resources #######################
########## Please write the following content in the local.settings.json file ##########
-----------------------------------------------------------------------------
# Azure-related environment settings

"AzureWebJobsStorage": "****************************",
"AzurePostgresqlJdbcurl": "jdbc:postgresql://documentsearch1.postgres.database.azure.com:5432/VECTOR_DB?sslmode=require",
"AzurePostgresqlUser": "azureuser",
"AzurePostgresqlPassword": "********",
"AzurePostgresqlDbTableName": "DOCUMENT_SEARCH_VECTOR",
"AzureBlobstorageName": "documentsearch1",
"AzureBlobstorageContainerName": "pdfs",
"AzureCosmosDbEndpoint": "https://documentsearchstatus1.documents.azure.com:443/",
"AzureCosmosDbKey": "********************",
"AzureCosmosDbDatabaseName": "documentregistrystatus",
"AzureCosmosDbContainerName": "status",
"AzureOpenaiUrl": "https://YOUR_OPENAI.openai.azure.com",
"AzureOpenaiModelName": "gpt-4",
"AzureOpenaiApiKey": "YOUR_OPENAI_ACCESS_KEY",
-----------------------------------------------------------------------------

### Please write the following content in the application.properties file for Spring Boot ###
-----------------------------------------------------------------------------

# Settings for Azure PostgreSQL connection information

azure.postgresql.jdbcurl=jdbc:postgresql://documentsearch1.postgres.database.azure.com:5432/VECTOR_DB?sslmode=require
azure.postgresql.user=azureuser
azure.postgresql.password=**********
azure.postgresql.db.table.name=DOCUMENT_SEARCH_VECTOR

# The following Blob-related settings

azure.blobstorage.name=documentsearch1
azure.blobstorage.container.name=pdfs

# Settings for Azure Cosmos DB

azure.cosmos.db.endpoint=https://documentsearchstatus1.documents.azure.com:443
azure.cosmos.db.key=********************************************
azure.cosmos.db.database.name=documentregistrystatus
azure.cosmos.db.container.name=status

# Settings for Azure OpenAI

azure.openai.url=https://YOUR_OPENAI.openai.azure.com
azure.openai.model.name=gpt-4
azure.openai.api.key=********************************************
-----------------------------------------------------------------------------

# After creating PostgreSQL, please connect using the following command:
-----------------------------------------------------------------------------
> psql -U azureuser -d VECTOR_DB \
     -h documentsearch1.postgres.database.azure.com

Auto-generated PostgreSQL password: **********

# Once connected to PostgreSQL, please execute the following command:
-------------------------------------------------------------
VECTOR_DB=> CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
VECTOR_DB=> CREATE EXTENSION IF NOT EXISTS "vector";

# Finally, execute the following command to create the TABLE:

VECTOR_DB=> CREATE TABLE IF NOT EXISTS DOCUMENT_SEARCH_VECTOR
                 (id uuid, embedding VECTOR(1536),
                  origntext varchar(8192), fileName varchar(2048),
                  pageNumber integer, PRIMARY KEY (id));
-----------------------------------------------------------------------------
```

> Note:  
> For the above OpenAI-related settings, please set the Connection URL, Model name, and API Kkey that were created in the Azure Portal, respectively.

#### 2.1.1 Setting PostgreSQL Extensions

After generating the PostgreSQL instance, please check if it is accessible from your local environment by executing the following command:

```bash
psql -U azureuser -d VECTOR_DB \
     -h documentsearch1.postgres.database.azure.com
```

> ※ The password is displayed in the terminal.

Once connected to PostgreSQL, please execute the following command to add the extensions. In the example below, the `UUID` and `pgvector` extensions are enabled.

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";
```

Lastly, please execute the following command to create a table that can utilize vectors.

```sql
CREATE TABLE IF NOT EXISTS DOCUMENT_SEARCH_VECTOR
                    (id uuid, embedding VECTOR(1536),
                    origntext varchar(8192), fileName varchar(2048),
                    pageNumber integer, PRIMARY KEY (id));
```

## 3. Running the Application

Once the environment setup is complete, please follow the steps below to run the application:

### 3.1Running Azure Functions (BlobUploadDetector)

Since the environment variables have been changed, please build Azure Functions first and then execute it.

```bash
> cd BlobUploadDetector
> mvn clean package
> mvn azure-functions:run
```

### 3.2 Running Spring Boot

Since the environment variables have been changed, please build Spring Boot first and then execute it.

```bash
> cd SpringBoot
> mvn clean package
> mvn spring-boot:run
```

### 3.3 Uploading Files to Blob Storage

You can connect to the Azure Portal and upload files individually, but to make it easier to upload files, we will use Azure Storage Explorer.

Please download and install Azure Storage Explorer from [Azure Storage Explorer Download](https://azure.microsoft.com/products/storage/storage-explorer/).

When you launch the `Azure Storage Explorer`, you will be prompted to connect with your Azure account. Please connect, and once connected, you will see a screen like the one below, where you can drag and drop multiple files to upload them all at once.

![Azure-Storage-Explorer](https://live.staticflickr.com/65535/52971844298_690e552bd4_b.jpg)

### 3.4 Verifying the Spring Boot Application

When you access `http://localhost:8080/`, you will see a screen like the one below. Please enter the keyword you want to search for in the text area and click the `Submit` button. The search results will then be displayed in a streaming format.

![PDF-Document-Search](https://live.staticflickr.com/65535/52971893208_4a609ecc49_b.jpg)

Additionally, if you click on the `Registered File List` link, you will see a list of files registered in the database.

![Registered-PDF-files](https://live.staticflickr.com/65535/52971893213_3ce16bcee5_b.jpg)

By clicking on the `Failed Registration File List` link, you will see a list of files that failed to register in the database.

![Failed-PDF-Files"](https://live.staticflickr.com/65535/52971577659_2c76c97669_b.jpg)

## 4. Points to Note for Application Implementation

### 4.1 Points to Note for the Implementation of Azure Functions (BlobUploadDetector)

First, we will explain the points to note for the implementation of `BlobUploadDetector`, which is implemented in Azure Functions.

#### 4.1.1 Extending the Execution Time of Azure Functions

By default, Azure Functions have a runtime limit of 5 to 30 minutes. If this limit is exceeded, Azure Functions will time out. However, when analyzing and processing PDF files with a large number of pages, the processing may not be completed within this time limit. Therefore, we have extended the execution time of Azure Functions.

In this sample, we have added the following settings to `host.json` to extend the execution time of Azure Functions indefinitely. However, please note that only the `Premium plan` and `Dedicated plan` can be set to unlimited. The `Consumption plan` cannot be set to unlimited.

```json
{
  "version": "2.0",
  "functionTimeout": "-1", 
}
```

Reference :  
[Function app timeout duration](https://learn.microsoft.com/azure/azure-functions/functions-scale#timeout)

### 4.1.2 Adjusting the Usage Interval of Azure OpenAI Embedding API

The maximum number of calls per minute for the Azure OpenAI `text-embedding-ada-002` varies depending on the instance. If the limit is exceeded, the Azure OpenAI Embedding API will return a 400 error. Therefore, please adjust the call interval of the Azure OpenAI Embedding API according to your environment.

```java
// Azure OpenAI Call Interval (milliseconds)
private final static int OPENAI_INVOCATION_INTERVAL = 20;
```

> Reference:  
> The above setting is for when the number of calls per minute for the `text-embedding-ada-002` model is set to the maximum allowable value of 240k Token/min (approximately 1440 req/min) in my environment. If the number of calls per minute is different, please adjust this setting accordingly. In some cases, it may be necessary to change the setting in units of 5-10 seconds instead of milliseconds.

You can check and change the number of calls per minute from the `Quotas` section in [Azure OpenAI Studio](https://oai.azure.com/portal/).

![open-ai-quotas](https://live.staticflickr.com/65535/52971493166_04d2a0dd79_b.jpg)

### 4.1.3 Setting Environment Variables in Azure Functions

In Azure Functions, environment variables are set in the `local.settings.json` file for local environments. When deploying to Azure Functions, the settings are configured in the `Configuration` section of Azure Functions.
  
> Note: 
> JBe aware that environment variables cannot be set in the property files under `src/main/resources/`, which are commonly used in Java.

The values of environment variables set in `local.settings.json` can be retrieved in the program by calling the `System.getenv()` method.

#### 4.1.4 Points to Consider When Changing the Container Name Created in Azure Blob Storage

If you want to change the value of `"AzureBlobstorageContainerName": "pdfs"`, in the environment variables, please also modify the following part of the `Function.java` source code. The value that can be specified for `path` requires a definition in constants, so it cannot be obtained from environment variables and set to `path`.

```java
    @FunctionName("ProcessUploadedFile")
    @StorageAccount("AzureWebJobsStorage")
    public void run(
            @BlobTrigger(
                    name = "content", path = "pdfs/{name}", dataType = "binary") byte[] content,
            @BindingName("name") String fileName,
            @BlobInput(name = "inputBlob", path = "pdfs/{name}",
                    dataType = "binary") byte[] inputBlob,
            final ExecutionContext context) throws UnsupportedEncodingException {
```

### 4.1.5 Points to Consider in PDF Parsing Process (Especially When Using Models Other Than ChatGPT-4)

The following steps are performed when converting PDF files to text:

1. Split the PDF file by the number of pages
2. Convert each split page to text
3. If the converted text contains `\n` and `multiple spaces`, remove them and replace with `a single white space character`
4. Measure the size of the converted text
5. If the text size is within the range of (7200-7500) characters and characters such as ".", "?", "!" are found, split the text at that point
6. If the above delimiter characters are not found up to MAX_SEPARATE_TOKEN_LENGTH (7500), forcibly split the text
7. Send the split text to Azure OpenAI Embedding API to obtain vectors
8. Register the vectors data into the PostgreSQL vector database table
9. Register and update the status of each process in Cosmos DB as appropriate

> Note:
> The `text-embedding-ada-002` and `gpt-4` models have a maximum token limit of 8192 per request. In our experience, specifying a value close to the maximum may cause errors depending on the request conditions. Therefore, we have set the maximum number of characters per page to 7500. However, to avoid forcibly splitting the text in the middle of a sentence as much as possible, we have implemented the process so that if there is a delimiter character within the range of 7200 to 7500 characters, the text can be split there. If you use `gpt-35 turbo`, the `MAX is 4000 tokens`, so the current MAX_SEPARATE_TOKEN_LENGTH value is too large. Please change this value.

### 4.1.6 About the Data to Insert

Based on my experience, I believe that inserting pages with a certain number of characters into the PostgreSQL database is more effective. For example, if you insert a page with only one line written, such as `Please refer to this for information on Azure Functions,` into the database, the vector values obtained with `text-embedding-ada-002` will have little difference from other data, resulting in a higher similarity. Therefore, by inserting pages with a certain number of characters, you can increase the difference in vector values.

In other words, to put it simply, if you search for the term `Azure Functions,` the above-mentioned page will be more likely to be found, regardless of what kind of text follows. Therefore, please register pages with a certain number of characters in the database.

### 4.2 Points to Consider in Spring Boot Implementation

Next, I will describe the points to consider when implementing a Spring Boot application.

### 4.2.1 Implementing as Server Sent Event

When sending requests to OpenAI, the Java SDK provides instances for both synchronous and asynchronous processing. In this case, I will use the `OpenAIAsyncClient` class for asynchronous processing. By using this, you can return results as a real-time stream.

Since the Server-Side returns results as a Realtime streaming, you can, of course, return the results to the client in Realtime using Server Sent Events.

To implement the Spring Boot application as a Server Sent Event, we will use Spring Boot's WebFlux. Additionally, when implementing SSE with WebFlux, we have added the following implementation to enable 1-to-1 communication:

The client's browser (JavaScript) generates a UUID and connects to `/openai-gpt4-sse-stream` with that UUID.

Below is the `JavaScript` code snipet.

```javascript
        let userId;
        let eventSource;

        window.onload = function () {
            userId = generateUUID();
            setupEventSource();
        };

        function setupEventSource() {
            eventSource = new EventSource("/openai-gpt4-sse-stream?userId=" + userId);
```

> Note :  
> The method of generating UUIDs in JavaScript is implemented very simply in this example.
> If you plan to use this in a production environment, please consider a more secure method for generating UUIDs.

When connecting to the server, the server registers the client's UUID and `Sinks.Many<String>` in a `1 to 1` relationship using a Map.

Below is an excerpt from the `Java` code.

```java
    private static Map<UUID, Sinks.Many<String>> userSinks;

    @GetMapping(path = "/openai-gpt4-sse-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<String> sseStream(@RequestParam UUID userId) {
        Sinks.Many<String> userSink = getUserSink(userId);
        if (userSink == null) {
            userSink = createUserSink(userId);
        }
        LOGGER.trace("USER ID IS ADDED: {}}", userId);
        return userSink.asFlux().delayElements(Duration.ofMillis(10));
    }

    private Sinks.Many<String> createUserSink(UUID userId) {
        Sinks.Many<String> userSink = Sinks.many().multicast().directBestEffort();
        userSinks.put(userId, userSink);
        LOGGER.debug("User ID: {} User Sink: {} is Added.", userId, userSink);
        return userSink;
    }

    // Get User Sinks
    private Sinks.Many<String> getUserSink(UUID userId) {
        return userSinks.get(userId);
    }
```

Then, by calling `tryEmitNext` on the `Sinks.Many<String>` associated with this UUID, you can return a string to the client's browser.

`userSink.tryEmitNext(jsonMessage);`

The above process is automatically performed when accessing the website with `window.onload`.

#### 4.2.２ Processing when a string is entered and the Submit button is pressed

The process when a search string is entered in the browser and the Submit button is pressed is as follows:
At the time of `Submit`, you also pass the `UUID` to `/openai-gpt4-sse-submit` and access it via POST.

Below is an excerpt from the `JavaScript` code.

```javascript
        function submitText() {
            let elements = document.querySelectorAll('#target *');
            elements.forEach(function (element) {
                element.remove();
            });
            const textFieldValue = document.getElementById("inputText").value;
            fetch("/openai-gpt4-sse-submit?userId=" + userId, {
                method: "POST",
                body: textFieldValue,
                headers: {
                    "Content-Type": "text/plain"
                }
            });
        }  
```

On the server side, the user-input string received with `@RequestBody` is vectorized using `text-embedding-ada-002`, and then a vector search is performed in PostgreSQL. The search results from PostgreSQL are processed by calling OpenAI's ChatGPT for summarization. Finally, the results are returned to the client's browser using Server Sent Events.

```java
    @PostMapping("/openai-gpt4-sse-submit")
    @ResponseBody
    public void openaiGpt4Sse(@RequestBody String inputText, @RequestParam UUID userId) {
        var userSink = getUserSink(userId);
        LOGGER.debug("InputText --------------: {}", inputText);
        // Receive user input and search for documents in the PostgreSQL Vector DB  
        findMostSimilarString(inputText).subscribe(findMostSimilarString -> {
            // Based on the search results of the documents, perform summarization using OpenAI and send the results to the client
            findMostSimilarString.forEach(docSummary -> {
                requestOpenAIToGetSummaryAndSendMessageToClient(docSummary, inputText, userSink);
            });
        });
    }
```

#### 4.2.3 Points to Consider in Asynchronous Implementation

When implementing with Spring WebFlux's asynchronous processing, all internal processing must be implemented as asynchronous. If you `block()` during intermediate processing, an error will occur.

For example, when sending a query to PostgreSQL and returning the results, instead of returning them as a `List<DocumentSummarizer>`, you return them as a `Mono<List<DocumentSummarizer>>`.

Also, when calling OpenAI, the implementation is done as follows, without blocking any processing:

```java
        // Send a request to OpenAI and send the results to the client
        client.getChatCompletionsStream(OPENAI_MODEL_NAME, new ChatCompletionsOptions(chatMessages))
                .doOnSubscribe(subscription -> {
                    // Send a request-event to create a DIV area in the HTML for displaying the link and result string
                    sendCreateAreaEvent(userSink, docSummary);
                    // Send a request-event in the HTML to display the link
                    sendCreateLinkEvent(userSink, docSummary);
                })
                .subscribe(chatCompletions -> {
                    // Send the results from OpenAI to the client via streaming
                    sendChatCompletionMessages(userSink, docSummary, chatCompletions, inputText);
                }, error -> {
                    LOGGER.error("Error Occurred: {}", error.getMessage());
                    userSink.tryEmitError(error);
                }, () -> {
                    LOGGER.debug("Completed");
                });
```

When processing asynchronously in a non-blocking manner, there is a possibility that multiple returned results may get mixed up if there are multiple answers. Therefore, to correctly identify which summary result corresponds to which search result, the UUID associated with the document (included in the docSummary) is also sent to the client.

For example, a request is sent in JSON format to create a display area associated with the documentID, in order to separate the display area for each document.

```java
    // Send a request-event in the HTML to create a DIV area for displaying the link and result string
    private void sendCreateAreaEvent(Sinks.Many<String> userSink, DocumentSummarizer docSummary) {
        var documentID = docSummary.id().toString();
        var createArea = new CreateAreaInHTML("create", documentID);
        var gson = new Gson();
        var jsonCreateArea = gson.toJson(createArea);
        LOGGER.debug("jsonCreateArea: {}", jsonCreateArea);
        userSink.tryEmitNext(jsonCreateArea);
        // wait few mill seconds
        intervalToSendClient();
    }
```

The JavaScript implementation that receives the above JSON is as follows.
In the example below, when the JSON `type` is received as the string `create`, an area corresponding to the documentID is created.

```javascript
                    const json = JSON.parse(data);
                    if (json.type === "create") {
                        var documentId = json.id;
                        // Add a child element under the target
                        addArea(documentId);
                        return;
                    } else if (json.type === "createLink") {
                        var documentId = json.id;
                        var link = json.link;
                        var fileName = json.fileName;
                        var pageNumber = json.pageNumber;
                        // Add a link to the link description part under the child
                        createLink(documentId, link, fileName, pageNumber);
                        return;
                    } else if (json.type === "addMessage") {
                        var documentId = json.id;
                        var content = json.content;
                        // Add text to the text description part under the child
                        addMessage(documentId, content);
                        return;
                    }
```

By implementing in this way, even when multiple search results are obtained, it is possible to display them separately for each search result.

#### 4.2.4 Points to Consider in Cosmos DB Implementation

The following content is addressed in the `create-env-en.sh ` script and is not particularly important, but it is mentioned here for the sake of information sharing.

To execute a query in Cosmos DB and perform sorting, the following SQL is executed:

`"SELECT * FROM c WHERE c.status = 'COMPLETED' ORDER BY c.fileName ASC, c.pageNumber ASC`

In Cosmos DB, when sorting with `ORDER BY`, it is `necessary to create an index` on the target property. If you do not create one, the query results will not be displayed correctly.

In this case, when creating the Cosmos DB container in the `create-env-en.sh ` script, the `cosmos-index-policy.json` file is read, and indexes are created for the `fileName` and `pageNumber` properties.

The following command is executed in the script.

```bash
az cosmosdb sql container create --account-name $COSMOS_DB_ACCOUNT_NAME -g $RESOURCE_GROUP_NAME --database-name $COSMOS_DB_DB_NAME --name $COSMOS_DB_CONTAINER_NAME_FOR_STATUS --partition-key-path "/id"  --throughput 400  --idx @cosmos-index-policy.json
```

#### 4.2.5 Points to Consider in Spring Data JPA Implementation

Initially, we considered implementing queries for PostgreSQL using Spring Data JPA, but we were unable to handle the `pgvector type` in PostgreSQL with Spring Data JPA. Therefore, we abandoned the implementation using Spring Data JPA for this project.

Despite trying various approaches, even implementing with `Native Query` resulted in errors, so I have implemented using standard JDBC.

## Additional Notes

At this point, there are still some unimplemented features. For example, we have not yet implemented the deletion-related functions (deleting Blob files matching the UUID of failed file registrations, deleting entries in CosmosDB). 

## In Conclusion

To reiterate, by using this service, you can search for any internal documents, various research papers, or any PDF files by simply uploading them to Azure Storage. When you actually perform a search, ChatGPT will summarize and display the relevant sections for you.

Furthermore, not only PDFs but also Word and Excel files, as well as other text documents, can be handled by utilizing libraries.

If you are interested in this content, please feel free to give it a try.
