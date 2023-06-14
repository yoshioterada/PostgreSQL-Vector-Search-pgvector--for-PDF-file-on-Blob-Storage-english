package com.yoshio3.entities;

public record CosmosDBDocument(String id, String fileName, CosmosDBDocumentStatus status, int pageNumber){}
