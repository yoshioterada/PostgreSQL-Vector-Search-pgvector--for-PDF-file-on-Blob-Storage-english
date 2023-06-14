package com.yoshio3.models;

public record CosmosDBDocument(String id, String fileName, CosmosDBDocumentStatus status, int pageNumber){}
