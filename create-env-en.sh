#!/bin/bash
set -e

function exit_trap() {
    if [ $? != 0 ]; then
        echo "Command [$BASH_COMMAND] is failed"
        exit 1
    fi
}
trap exit_trap ERR

####################### Setting Environment Variables for Creating Azure Resources #######################
# Configuring the Resource Group and location settings
export RESOURCE_GROUP_NAME=Document-Search-Vector5
export DEPLOY_LOCATION=japaneast

# Settings for Azure PostgreSQL Flexible Server (In my environment, there are construction restrictions, so it is set to eastus)
export POSTGRES_INSTALL_LOCATION=eastus
export POSTGRES_SERVER_NAME=yoshiodocumentsearch5
export POSTGRES_USER_NAME=azuzreuser
export POSTGRES_USER_PASS='!'$(head -c 12 /dev/urandom | base64 | tr -dc '[:alpha:]'| fold -w 8 | head -n 1)$RANDOM
export POSTGRES_DB_NAME=VECTOR_DB
export POSTGRES_TABLE_NAME=DOCUMENT_SEARCH_VECTOR
export PUBLIC_IP=$(curl ifconfig.io -4)

# Settings for Azure Blob Storage
export BLOB_STORAGE_ACCOUNT_NAME=yoshiodocumentsearch5

# Note: If you change the values below, you will also need to modify the implementation part of the BlobTrigger in Functions.java.
export BLOB_CONTAINER_NAME_FOR_PDF=pdfs

#  Settings for Azure Cosmos DB
export COSMOS_DB_ACCOUNT_NAME=yoshiodocumentsearchstatus5
export COSMOS_DB_DB_NAME=documentregistrystatus
export COSMOS_DB_CONTAINER_NAME_FOR_STATUS=status

# Obtaining the Azure subscription ID (If you are using the default subscription, you do not need to make the changes below)
export SUBSCRIPTION_ID="$(az account list --query "[?isDefault].id" -o tsv)"
####################### Azure のリソースを作成するための環境変数の設定 #######################


# Create Resource Group
az group create --name $RESOURCE_GROUP_NAME --location $DEPLOY_LOCATION

# Create Azure PostgreSQL Flexible Server
az postgres flexible-server create --name $POSTGRES_SERVER_NAME \
    -g $RESOURCE_GROUP_NAME \
    --location $POSTGRES_INSTALL_LOCATION \
    --admin-user $POSTGRES_USER_NAME \
    --admin-password $POSTGRES_USER_PASS \
    --version 14 \
    --public-access $PUBLIC_IP --yes
# Configure Azure PostgreSQL Flexible Server Firewall Rule
az postgres flexible-server firewall-rule create \
    -g $RESOURCE_GROUP_NAME \
    -n $POSTGRES_SERVER_NAME \
    -r AllowAllAzureIPs \
    --start-ip-address 0.0.0.0 \
    --end-ip-address 255.255.255.255
# Create a DB for Azure PostgreSQL Flexible Server
az postgres flexible-server db create \
    -g $RESOURCE_GROUP_NAME \
    -s $POSTGRES_SERVER_NAME \
    -d $POSTGRES_DB_NAME
# Configure Localization for Azure PostgreSQL Flexible Server   
az postgres flexible-server parameter set \
    -g $RESOURCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name lc_monetary --value "ja_JP.utf-8"
# Configure Localization for Azure PostgreSQL Flexible Server
az postgres flexible-server parameter set \
    -g $RESOURCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name lc_numeric --value "ja_JP.utf-8"
# Configure Timezone for Azure PostgreSQL Flexible Server DB   
az postgres flexible-server parameter set \
    -g $RESOURCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name timezone --value "Asia/Tokyo"
# Configure Extension Functionality for Azure PostgreSQL Flexible Server
az postgres flexible-server parameter set \
    -g $RESOURCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name azure.extensions --value "VECTOR,UUID-OSSP"


# Create Azure Blob Storage Account
az storage account create  -g $RESOURCE_GROUP_NAME --name $BLOB_STORAGE_ACCOUNT_NAME --location $DEPLOY_LOCATION --sku Standard_ZRS  --encryption-services blob
# Obtain Azure Blob Storage Account Access Key
export BLOB_ACCOUNT_KEY=$(az storage account keys list --account-name $BLOB_STORAGE_ACCOUNT_NAME -g $RESOURCE_GROUP_NAME --query "[0].value" -o tsv)
# Obtain Azure Blob Storage Connection String
export BLOB_CONNECTION_STRING=$(az storage account  show-connection-string -g $RESOURCE_GROUP_NAME --name $BLOB_STORAGE_ACCOUNT_NAME --query "connectionString" --output tsv)

# Create a Container for Azure Blob Storage
az storage container create --account-name $BLOB_STORAGE_ACCOUNT_NAME --name $BLOB_CONTAINER_NAME_FOR_PDF --account-key $BLOB_ACCOUNT_KEY

# Configure Access Permission for Azure Blob Storage
az storage container set-permission --name $BLOB_CONTAINER_NAME_FOR_PDF --public-access container --account-name $BLOB_STORAGE_ACCOUNT_NAME  --account-key $BLOB_ACCOUNT_KEY


# Create Azure Cosmos DB Account, DB and Container. Obtain Access Key
az cosmosdb create -g $RESOURCE_GROUP_NAME --name $COSMOS_DB_ACCOUNT_NAME --kind GlobalDocumentDB --locations regionName=$DEPLOY_LOCATION failoverPriority=0 --default-consistency-level "Session"  
az cosmosdb sql database create --account-name $COSMOS_DB_ACCOUNT_NAME -g $RESOURCE_GROUP_NAME --name $COSMOS_DB_DB_NAME  
export COSMOS_DB_ACCESS_KEY=$(az cosmosdb keys list -g $RESOURCE_GROUP_NAME --name $COSMOS_DB_ACCOUNT_NAME --type keys --query "primaryMasterKey" -o tsv)

az cosmosdb sql container create --account-name $COSMOS_DB_ACCOUNT_NAME -g $RESOURCE_GROUP_NAME --database-name $COSMOS_DB_DB_NAME --name $COSMOS_DB_CONTAINER_NAME_FOR_STATUS --partition-key-path "/id"  --throughput 400  --idx @cosmos-index-policy.json
# Configure the policy to execute "ORDER BY c.fileName ASC, c.pageNumber ASC"
# 詳細：https://learn.microsoft.com/azure/cosmos-db/nosql/how-to-manage-indexing-policy?tabs=dotnetv3%2Cpythonv3#composite-index-defined-for-name-asc-age-asc-and-name-asc-age-desc

echo "########## Please write the following content in the local.settings.json file  ##########"
echo "-----------------------------------------------------------------------------"
echo "# Azure-related environment settings"
echo ""
echo "\"AzureWebJobsStorage\": \"$BLOB_CONNECTION_STRING\","
echo "\"AzurePostgresqlJdbcurl\": \"jdbc:postgresql://$POSTGRES_SERVER_NAME.postgres.database.azure.com:5432/$POSTGRES_DB_NAME?sslmode=require\","
echo "\"AzurePostgresqlUser\": \"$POSTGRES_USER_NAME\","
echo "\"AzurePostgresqlPassword\": \"$POSTGRES_USER_PASS\","
echo "\"AzurePostgresqlDbTableName\": \"$POSTGRES_TABLE_NAME\","
echo "\"AzureBlobstorageName\": \"$BLOB_STORAGE_ACCOUNT_NAME\","
echo "\"AzureBlobstorageContainerName\": \"$BLOB_CONTAINER_NAME_FOR_PDF\","
echo "\"AzureCosmosDbEndpoint\": \"https://$COSMOS_DB_ACCOUNT_NAME.documents.azure.com:443/\","
echo "\"AzureCosmosDbKey\": \"$COSMOS_DB_ACCESS_KEY\","
echo "\"AzureCosmosDbDatabaseName\": \"$COSMOS_DB_DB_NAME\","
echo "\"AzureCosmosDbContainerName\": \"$COSMOS_DB_CONTAINER_NAME_FOR_STATUS\","
echo "\"AzureOpenaiUrl\": \"https://YOUR_OPENAI.openai.azure.com\","
echo "\"AzureOpenaiModelName\": \"gpt-4\","
echo "\"AzureOpenaiApiKey\": \"YOUR_OPENAI_ACCESS_KEY\","
echo "-----------------------------------------------------------------------------"
echo ""
echo "### Please write the following content in the application.properties file for Spring Boot ###"
echo "-----------------------------------------------------------------------------"
echo ""
echo "# Settings for Azure PostgreSQL connection information"
echo ""
echo "azure.postgresql.jdbcurl=jdbc:postgresql://$POSTGRES_SERVER_NAME.postgres.database.azure.com:5432/$POSTGRES_DB_NAME?sslmode=require"
echo "azure.postgresql.user=$POSTGRES_USER_NAME"
echo "azure.postgresql.password=$POSTGRES_USER_PASS"
echo "azure.postgresql.db.table.name=$POSTGRES_TABLE_NAME"
echo ""
echo "# The following Blob-related settings"
echo ""
echo "azure.blobstorage.name=$BLOB_STORAGE_ACCOUNT_NAME"
echo "azure.blobstorage.container.name=$BLOB_CONTAINER_NAME_FOR_PDF"
echo ""
echo "# Settings for Azure Cosmos DB"
echo ""
echo "azure.cosmos.db.endpoint=https://$COSMOS_DB_ACCOUNT_NAME.documents.azure.com:443"
echo "azure.cosmos.db.key=$COSMOS_DB_ACCESS_KEY"
echo "azure.cosmos.db.database.name=$COSMOS_DB_DB_NAME"
echo "azure.cosmos.db.container.name=$COSMOS_DB_CONTAINER_NAME_FOR_STATUS"
echo ""
echo "# Settings for Azure OpenAI"
echo ""
echo "azure.openai.url=https://YOUR_OPENAI.openai.azure.com"
echo "azure.openai.model.name=gpt4"
echo "azure.openai.api.key=$YOUR_OPENAI_ACCESS_KEY"
echo "-----------------------------------------------------------------------------"
echo ""
echo "# After creating PostgreSQL, please connect using the following command:"
echo "-----------------------------------------------------------------------------"
echo "> psql -U $POSTGRES_USER_NAME -d $POSTGRES_DB_NAME \\"
echo "     -h $POSTGRES_SERVER_NAME.postgres.database.azure.com"
echo ""
echo "Auto-generated PostgreSQL password: $POSTGRES_USER_PASS"
echo ""
echo "# Once connected to PostgreSQL, please execute the following command:"
echo "-------------------------------------------------------------"
echo "$POSTGRES_DB_NAME=> CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";"
echo "$POSTGRES_DB_NAME=> CREATE EXTENSION IF NOT EXISTS \"vector\";"
echo ""
echo "# Finally, execute the following command to create the TABLE:"
echo ""
echo "$POSTGRES_DB_NAME=> CREATE TABLE IF NOT EXISTS $POSTGRES_TABLE_NAME"
echo "                 (id uuid, embedding VECTOR(1536),"
echo "                  origntext varchar(8192), fileName varchar(2048),"
echo "                  pageNumber integer, PRIMARY KEY (id));"
echo "-----------------------------------------------------------------------------"


