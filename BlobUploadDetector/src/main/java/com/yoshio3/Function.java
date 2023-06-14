package com.yoshio3;

import com.microsoft.azure.functions.annotation.*;
import com.yoshio3.models.CosmosDBDocumentStatus;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.AzureKeyCredential;
import com.microsoft.azure.functions.*;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;


public class Function {
    // Azure OpenAI API key
    private static final String OPENAI_API_KEY;
    // Azure OpenAI instance URL
    private static final String OPENAI_URL;

    // Azure PostgreSQL JDBC URL
    private static final String POSTGRESQL_JDBC_URL;
    // Azure PostgreSQL username
    private static final String POSTGRESQL_USER;
    // Azure PostgreSQL password
    private static final String POSTGRESQL_PASSWORD;
    // Azure PostgreSQL table name
    private static final String POSTGRESQL_TABLE_NAME;
    // Maximum number of characters per page (if exceeded, the page will be split and processed)
    private static final int MAX_SEPARATE_TOKEN_LENGTH = 7500;
    // Azure OpenAI client instance
    private OpenAIClient client;

    // Azure OpenAI call interval (in milliseconds)
    private final static int OPENAI_INVOCATION_INTERVAL = 20;

    //Azure OpenAI call retry count
    private static final int MAX_OPENAI_INVOCATION_RETRY_COUNT = 3;
    // Azure Cosmos DB client instance
    CosmosDBUtil cosmosDBUtil;

    static {
        OPENAI_API_KEY = System.getenv("AzureOpenaiApiKey");
        OPENAI_URL = System.getenv("AzureOpenaiUrl");

        POSTGRESQL_JDBC_URL = System.getenv("AzurePostgresqlJdbcurl");
        POSTGRESQL_USER = System.getenv("AzurePostgresqlUser");
        POSTGRESQL_PASSWORD = System.getenv("AzurePostgresqlPassword");
        POSTGRESQL_TABLE_NAME = System.getenv("AzurePostgresqlDbTableName");
    }

    public Function() {
        client = new OpenAIClientBuilder().credential(new AzureKeyCredential(OPENAI_API_KEY))
                .endpoint(OPENAI_URL).buildClient();
        cosmosDBUtil = new CosmosDBUtil();
    }

    // Note:
    // If you change "azure.blobstorage.container.name=pdfs" in applications.properties,
    // you also need to change the path in @BlobTrigger and @BlobInput. Default value: (pdfs/{name})
    // The reason is that the values that can be specified in the path are limited to those defined in constants,
    // and cannot be obtained from properties.
    @FunctionName("ProcessUploadedFile")
    @StorageAccount("AzureWebJobsStorage")
    public void run(
            @BlobTrigger(
                    name = "content", path = "pdfs/{name}", dataType = "binary") byte[] content,
            @BindingName("name") String fileName,
            @BlobInput(name = "inputBlob", path = "pdfs/{name}",
                    dataType = "binary") byte[] inputBlob,
            final ExecutionContext context) throws UnsupportedEncodingException {
        String encodedFileName = URLEncoder.encode(fileName, "UTF-8");
        context.getLogger().info(encodedFileName);

        if (fileName.endsWith(".pdf")) {
            var extractPDFtoTextByPage = extractPDFtoTextByPage(content, context);
            extractPDFtoTextByPage.forEach(pageInfo -> insertDataToPostgreSQL(pageInfo.text(),
                    context, fileName, pageInfo.pageNumber()));
        }
    }

    private List<PageInfo> extractPDFtoTextByPage(byte[] content, ExecutionContext context) {
        // String pdfFilePath = "/tmp/azure-app-service.pdf";
        List<PageInfo> allPages = new ArrayList<>();

        try (PDDocument document = PDDocument.load(content)) {
            // try (PDDocument document = PDDocument.load(new File(pdfFilePath))) {
            PDFTextStripper textStripper = new PDFTextStripper();
            int numberOfPages = document.getNumberOfPages();

            // Loop through the number of pages in the PDF file
            IntStream.rangeClosed(1, numberOfPages).forEach(pageNumber -> {
                try {
                    textStripper.setStartPage(pageNumber);
                    textStripper.setEndPage(pageNumber);
                    String pageText = textStripper.getText(document);
                    // Replace newline characters with whitespace
                    pageText = pageText.replace("\n", " ");
                    pageText = pageText.replaceAll("\\s{2,}", " ");

                    // If the text on one page exceeds 7500 characters, split it
                    if (pageText.length() > MAX_SEPARATE_TOKEN_LENGTH) {
                        context.getLogger().fine("Split text: " + pageText.length());
                        List<String> splitText = splitText(pageText, MAX_SEPARATE_TOKEN_LENGTH);
                        splitText.forEach(text -> {
                            PageInfo pageInfo = new PageInfo(pageNumber, text);
                            allPages.add(pageInfo);
                        });
                    } else {
                        PageInfo pageInfo = new PageInfo(pageNumber, pageText);
                        allPages.add(pageInfo);
                    }
                } catch (IOException e) {
                    context.getLogger()
                            .severe("Error while extracting text from PDF: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            context.getLogger().severe("Error while extracting text from PDF: " + e.getMessage());
            e.printStackTrace();
        }
        return allPages;
    }

    // Inserting Vector data into PostgreSQL (text-embedding-ada-001)
    private void insertDataToPostgreSQL(String originText, ExecutionContext context,
            String fileName, int pageNumber) {
        UUID uuid = UUID.randomUUID();
        String uuidString = uuid.toString();
        try {
            cosmosDBUtil
                    .createDocument(uuidString, fileName,
                            CosmosDBDocumentStatus.PAGE_SEPARATE_FINISHED, pageNumber, context)
                    .block();

            // Call OpenAI Text Embedding (text-embedding-ada-002) to obtain vector array
            List<Double> embedding = invokeTextEmbedding(uuidString, originText, context);
            cosmosDBUtil.updateStatus(uuidString, CosmosDBDocumentStatus.FINISH_OAI_INVOCATION,
                    context);

            // Insert vector array into PostgreSQL
            var insertSql = "INSERT INTO " + POSTGRESQL_TABLE_NAME
                    + " (id, embedding, origntext, fileName, pageNumber) VALUES (?, ?::vector, ?, ?, ?)";
            try (var connection = DriverManager.getConnection(POSTGRESQL_JDBC_URL, POSTGRESQL_USER,
                    POSTGRESQL_PASSWORD);
                    PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
                insertStatement.setObject(1, uuid);
                insertStatement.setArray(2,
                        connection.createArrayOf("double", embedding.toArray()));
                insertStatement.setString(3, originText);
                insertStatement.setString(4, fileName);
                insertStatement.setInt(5, pageNumber);
                insertStatement.executeUpdate();
                cosmosDBUtil.updateStatus(uuidString, CosmosDBDocumentStatus.FINISH_DB_INSERTION,
                        context);
            }
            // Sleep to avoid errors when sending a large number of requests (adjustable)
            sleep();
        } catch (Exception e) {
            context.getLogger()
                    .severe("Error while inserting data to PostgreSQL: " + e.getMessage());
            cosmosDBUtil.updateStatus(uuidString, CosmosDBDocumentStatus.FAILED_DB_INSERTION,
                    context);
            Thread.currentThread().interrupt();
        }
        cosmosDBUtil.updateStatus(uuidString, CosmosDBDocumentStatus.COMPLETED, context);
    }

    /**
     * Invoke Text Embedding  (text-embedding-ada-002)
     */
    private List<Double> invokeTextEmbedding(String uuid, String originalText,
            ExecutionContext context) {
        List<Double> embedding = new ArrayList<>();
        var embeddingsOptions = new EmbeddingsOptions(Arrays.asList(originalText));

        int retryCount = 0;
        while (retryCount < MAX_OPENAI_INVOCATION_RETRY_COUNT) {
            try {
                // Call OpenAI API
                var result = client.getEmbeddings("text-embedding-ada-002", embeddingsOptions);
                // Obtain usage information (number of tokens used)
                var usage = result.getUsage();
                context.getLogger().info("Number of Prompt Token: " + usage.getPromptTokens()
                        + "Number of Total Token: " + usage.getTotalTokens());
                // Retrieve vector array
                var findFirst = result.getData().stream().findFirst();
                if (findFirst.isPresent()) {
                    embedding.addAll(findFirst.get().getEmbedding());
                }
                break;
            } catch (Exception e) {
                context.getLogger().severe("Error while invoking OpenAI: " + e.getMessage());
                cosmosDBUtil.updateStatus(uuid, CosmosDBDocumentStatus.RETRY_OAI_INVOCATION,
                        context);
                retryCount++;
                retrySleep();
            }
        }
        return embedding;
    }

    // The input string is split into approximately 7500-character segments, with divisions occurring at punctuation marks.
    // Based on experience, splitting at 8000 tokens out of 8192 may cause overflow when issuing commands.
    private List<String> splitText(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        int textLength = text.length();

        while (textLength > maxLength) {
            int splitIndex = findSplitIndex(text, maxLength);
            chunks.add(text.substring(0, splitIndex));
            text = text.substring(splitIndex);
            textLength = text.length();
        }
        chunks.add(text);
        return chunks;
    }

    // The input string is divided into segments of around 7500 characters, with divisions occurring at punctuation marks (such as periods, question marks, and exclamation marks).
    // If no suitable punctuation is found, the text will simply be split every 7500 characters.
    private int findSplitIndex(String text, int maxLength) {
        // Search for punctuation marks within the range of 7200-7500 characters
        int start = maxLength - 300;
        int splitIndex = maxLength;
        while (splitIndex > start) {
            char c = text.charAt(splitIndex);
            if (isPunctuation(c)) {
                break;
            }
            splitIndex--;
        }
        if (splitIndex == 0) {
            splitIndex = maxLength;
        }
        return splitIndex;
    }

    // Determination of punctuation marks
    private boolean isPunctuation(char c) {
        return c == '.' || c == ':' || c == ';' || c == '?' || c == '!';
    }

    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(OPENAI_INVOCATION_INTERVAL);
        } catch (InterruptedException interruptedException) {
            interruptedException.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }

    private void retrySleep() {
        try {
            TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException interruptedException) {
            interruptedException.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }

}
