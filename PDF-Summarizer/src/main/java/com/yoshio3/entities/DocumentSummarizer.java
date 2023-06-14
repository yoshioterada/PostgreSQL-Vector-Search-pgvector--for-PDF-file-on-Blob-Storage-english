package com.yoshio3.entities;

import java.io.Serializable;
import java.util.UUID;

public record DocumentSummarizer(UUID id, Double[] embedding, String origntext, String filename, int pageNumber) implements Serializable {}