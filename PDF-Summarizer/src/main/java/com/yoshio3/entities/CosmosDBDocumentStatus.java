package com.yoshio3.entities;

public enum CosmosDBDocumentStatus {

    PAGE_SEPARATE_FINISHED("page_separate_finished"), 
    RETRY_OAI_INVOCATION("retry_oai_invocation"),
    FINISH_OAI_INVOCATION("finish_oai_invocation"),
    FINISH_DB_INSERTION("finish_db_insertion"),
    FAILED_DB_INSERTION("failed_db_insertion"),
    COMPLETED("completed");
  
    private final String status;  
  
    CosmosDBDocumentStatus(String status) {  
        this.status = status;  
    }  
  
    public String getStatus() {  
        return status;  
    }  
} 