#!/bin/bash
set -e

function exit_trap() {
    if [ $? != 0 ]; then
        echo "Command [$BASH_COMMAND] is failed"
        exit 1
    fi
}
trap exit_trap ERR

####################### Azure のリソースを作成するための環境変数の設定 #######################
# 作成するリソースグループとロケーション設定
export RESOURCE_GROUP_NAME=Document-Search-Vector5
export DEPLOY_LOCATION=japaneast

# Azure PostgreSQL Flexible Server に関する設定(私の環境では構築制限があるため eastus に設定)
export POSTGRES_INSTALL_LOCATION=eastus
export POSTGRES_SERVER_NAME=yoshiodocumentsearch5
export POSTGRES_USER_NAME=yoterada
export POSTGRES_USER_PASS='!'$(head -c 12 /dev/urandom | base64 | tr -dc '[:alpha:]'| fold -w 8 | head -n 1)$RANDOM
export POSTGRES_DB_NAME=VECTOR_DB
export POSTGRES_TABLE_NAME=DOCUMENT_SEARCH_VECTOR
export PUBLIC_IP=$(curl ifconfig.io -4)

# Azure Blob ストレージに関する設定
export BLOB_STORAGE_ACCOUNT_NAME=yoshiodocumentsearch5

#　注意： 下記の値を変更する場合は、Functions.java の BlobTrigger の実装部分も変更する必要があります。
export BLOB_CONTAINER_NAME_FOR_PDF=pdfs

# Azure Cosmos DB に関する設定
export COSMOS_DB_ACCOUNT_NAME=yoshiodocumentsearchstatus5
export COSMOS_DB_DB_NAME=documentregistrystatus
export COSMOS_DB_CONTAINER_NAME_FOR_STATUS=status

# Azure のサブスクリプション ID の取得(デフォルトのサブスクリプションを使用する場合は下記の変更は不要)
export SUBSCRIPTION_ID="$(az account list --query "[?isDefault].id" -o tsv)"
####################### Azure のリソースを作成するための環境変数の設定 #######################


# リソース・グループの作成
az group create --name $RESOURCE_GROUP_NAME --location $DEPLOY_LOCATION

# Azure PostgreSQL Flexible Server の作成
az postgres flexible-server create --name $POSTGRES_SERVER_NAME \
    -g $RESOURCE_GROUP_NAME \
    --location $POSTGRES_INSTALL_LOCATION \
    --admin-user $POSTGRES_USER_NAME \
    --admin-password $POSTGRES_USER_PASS \
    --version 14 \
    --public-access $PUBLIC_IP --yes
# Azure PostgreSQL Flexible Server Firewall Rule の作成
az postgres flexible-server firewall-rule create \
    -g $RESOURCE_GROUP_NAME \
    -n $POSTGRES_SERVER_NAME \
    -r AllowAllAzureIPs \
    --start-ip-address 0.0.0.0 \
    --end-ip-address 255.255.255.255
# Azure PostgreSQL Flexible Server DB の作成
az postgres flexible-server db create \
    -g $RESOURCE_GROUP_NAME \
    -s $POSTGRES_SERVER_NAME \
    -d $POSTGRES_DB_NAME
# Azure PostgreSQL Flexible Server DB の日本語設定    
az postgres flexible-server parameter set \
    -g $RESOURCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name lc_monetary --value "ja_JP.utf-8"
# Azure PostgreSQL Flexible Server DB の日本語設定    
az postgres flexible-server parameter set \
    -g $RESOURCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name lc_numeric --value "ja_JP.utf-8"
# Azure PostgreSQL Flexible Server DB のタイムゾーン設定    
az postgres flexible-server parameter set \
    -g $RESOURCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name timezone --value "Asia/Tokyo"
# Azure PostgreSQL Flexible Server DB の拡張機能設定
az postgres flexible-server parameter set \
    -g $RESOURCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name azure.extensions --value "VECTOR,UUID-OSSP"


# Azure Blob ストレージ・アカウントの作成
az storage account create  -g $RESOURCE_GROUP_NAME --name $BLOB_STORAGE_ACCOUNT_NAME --location $DEPLOY_LOCATION --sku Standard_ZRS  --encryption-services blob
# Azure Blob ストレージ・アカウントのアクセス・キーの取得
export BLOB_ACCOUNT_KEY=$(az storage account keys list --account-name $BLOB_STORAGE_ACCOUNT_NAME -g $RESOURCE_GROUP_NAME --query "[0].value" -o tsv)
# Azure Blob ストレージ・アカウントの接続文字列の取得
export BLOB_CONNECTION_STRING=$(az storage account  show-connection-string -g $RESOURCE_GROUP_NAME --name $BLOB_STORAGE_ACCOUNT_NAME --query "connectionString" --output tsv)

# Azure Blob ストレージ・コンテナの作成
az storage container create --account-name $BLOB_STORAGE_ACCOUNT_NAME --name $BLOB_CONTAINER_NAME_FOR_PDF --account-key $BLOB_ACCOUNT_KEY

# Azure Blob のアクセス権限の設定
az storage container set-permission --name $BLOB_CONTAINER_NAME_FOR_PDF --public-access container --account-name $BLOB_STORAGE_ACCOUNT_NAME  --account-key $BLOB_ACCOUNT_KEY


# Azure Cosmos DB アカウント DB, コンテナの作成、アクセス・キーの取得
az cosmosdb create -g $RESOURCE_GROUP_NAME --name $COSMOS_DB_ACCOUNT_NAME --kind GlobalDocumentDB --locations regionName=$DEPLOY_LOCATION failoverPriority=0 --default-consistency-level "Session"  
az cosmosdb sql database create --account-name $COSMOS_DB_ACCOUNT_NAME -g $RESOURCE_GROUP_NAME --name $COSMOS_DB_DB_NAME  
export COSMOS_DB_ACCESS_KEY=$(az cosmosdb keys list -g $RESOURCE_GROUP_NAME --name $COSMOS_DB_ACCOUNT_NAME --type keys --query "primaryMasterKey" -o tsv)

az cosmosdb sql container create --account-name $COSMOS_DB_ACCOUNT_NAME -g $RESOURCE_GROUP_NAME --database-name $COSMOS_DB_DB_NAME --name $COSMOS_DB_CONTAINER_NAME_FOR_STATUS --partition-key-path "/id"  --throughput 400  --idx @cosmos-index-policy.json
# Cosmos DB で ORDER BY c.fileName ASC, c.pageNumber ASC を実行できるようにポリシーを設定
# 詳細：https://learn.microsoft.com/azure/cosmos-db/nosql/how-to-manage-indexing-policy?tabs=dotnetv3%2Cpythonv3#composite-index-defined-for-name-asc-age-asc-and-name-asc-age-desc

echo "########## 下記の内容を local.settings.json file に書いてください ##########"
echo "-----------------------------------------------------------------------------"
echo "# Azure 関連の環境設定設定"
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
echo "### 下記の内容を別実装の Spring Boot の application.properties に書いてください ###"
echo "-----------------------------------------------------------------------------"
echo ""
echo "# Azure PostgreSQL 関連の接続情報の設定"
echo ""
echo "azure.postgresql.jdbcurl=jdbc:postgresql://$POSTGRES_SERVER_NAME.postgres.database.azure.com:5432/$POSTGRES_DB_NAME?sslmode=require"
echo "azure.postgresql.user=$POSTGRES_USER_NAME"
echo "azure.postgresql.password=$POSTGRES_USER_PASS"
echo "azure.postgresql.db.table.name=$POSTGRES_TABLE_NAME"
echo ""
echo "# 下記の Blob 関連の設定"
echo ""
echo "azure.blobstorage.name=$BLOB_STORAGE_ACCOUNT_NAME"
echo "azure.blobstorage.container.name=$BLOB_CONTAINER_NAME_FOR_PDF"
echo ""
echo "# Azure Cosmos DB 関連の設定"
echo ""
echo "azure.cosmos.db.endpoint=https://$COSMOS_DB_ACCOUNT_NAME.documents.azure.com:443"
echo "azure.cosmos.db.key=$COSMOS_DB_ACCESS_KEY"
echo "azure.cosmos.db.database.name=$COSMOS_DB_DB_NAME"
echo "azure.cosmos.db.container.name=$COSMOS_DB_CONTAINER_NAME_FOR_STATUS"
echo ""
echo "# Azure OpenAI 関連の設定"
echo ""
echo "azure.openai.url=https://YOUR_OPENAI.openai.azure.com"
echo "azure.openai.model.name=gpt4"
echo "azure.openai.api.key=$YOUR_OPENAI_ACCESS_KEY"
echo "-----------------------------------------------------------------------------"
echo ""
echo "# PostgreSQL を作成したのち、下記のコマンドで接続してください"
echo "-----------------------------------------------------------------------------"
echo "> psql -U $POSTGRES_USER_NAME -d $POSTGRES_DB_NAME \\"
echo "     -h $POSTGRES_SERVER_NAME.postgres.database.azure.com"
echo ""
echo "自動生成した PostgreSQL のパスワード: $POSTGRES_USER_PASS"
echo ""
echo "# PostgreSQL に接続したのち、下記のコマンドを実行してください"
echo "-------------------------------------------------------------"
echo "$POSTGRES_DB_NAME=> CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";"
echo "$POSTGRES_DB_NAME=> CREATE EXTENSION IF NOT EXISTS \"vector\";"
echo ""
echo "# 最後に、下記のコマンドを実行して TABLE を作成してください"
echo ""
echo "$POSTGRES_DB_NAME=> CREATE TABLE IF NOT EXISTS $POSTGRES_TABLE_NAME"
echo "                 (id uuid, embedding VECTOR(1536),"
echo "                  origntext varchar(8192), fileName varchar(2048),"
echo "                  pageNumber integer, PRIMARY KEY (id));"
echo "-----------------------------------------------------------------------------"


