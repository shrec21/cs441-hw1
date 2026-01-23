# 🔍 Distributed RAG Index Builder (Scala + Spark)

A scalable **Retrieval-Augmented Generation (RAG) index builder** designed to process large collections of research PDFs, chunk text intelligently, compute embeddings, and prepare data for efficient semantic search using vector indexes.

Built as part of **CS 441** to explore distributed systems, IR pipelines, and modern LLM retrieval architectures.

---

## 🚀 Project Overview

This project implements the **indexing side of a RAG system**, focusing on:

- Distributed PDF ingestion
- Clean text extraction
- Overlap-aware semantic chunking
- Embedding generation at scale
- Corpus-level statistics for analysis
- Preparation for vector search (HNSW / Lucene)

The system is designed to scale from **local development** to **cluster-based execution** using Apache Spark.

---

## 🧠 Why This Project Matters

Modern LLM applications fail without **high-quality retrieval**.  
This project addresses real-world challenges such as:

- Handling noisy academic PDFs
- Preserving semantic continuity across chunks
- Scaling embedding computation
- Preparing data for low-latency vector search

This mirrors **production RAG pipelines** used in search, research tooling, and enterprise AI systems.

---

## 🏗️ Architecture

```text
PDFs
 ↓
Text Extraction
 ↓
Cleaning & Normalization
 ↓
Semantic Chunking (with overlap)
 ↓
Embedding Generation
 ↓
Corpus Statistics
 ↓
Vector Index (HNSW / Lucene)
```
---

 ## 🛠️ Tech Stack
 
 - **Language:** Scala   
 - **Build Tool:** sbt  
 - **Embeddings:** Ollama (local LLM embedding models)  
 - **Vector Indexing:** Lucene (HNSW)  
 - **Data Sources:** Research PDFs  
 - **Version Control:** Git & GitHub

