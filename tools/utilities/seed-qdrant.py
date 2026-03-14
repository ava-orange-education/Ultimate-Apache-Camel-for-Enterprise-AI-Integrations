#!/usr/bin/env python3
"""
seed-qdrant.py — Seeds Qdrant with all synthetic documents.

Requirements:
    pip install qdrant-client sentence-transformers

Usage:
    python tools/utilities/seed-qdrant.py \
        --host localhost --port 6333 \
        --docs-dir datasets/synthetic/text
"""

import argparse
import json
import os
import sys
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Seed Qdrant with synthetic documents")
    parser.add_argument("--host",     default="localhost")
    parser.add_argument("--port",     default=6333, type=int)
    parser.add_argument("--collection", default="documents")
    parser.add_argument("--docs-dir", default="datasets/synthetic/text")
    args = parser.parse_args()

    try:
        from qdrant_client import QdrantClient
        from qdrant_client.models import Distance, VectorParams, PointStruct
        from sentence_transformers import SentenceTransformer
    except ImportError:
        print("Install: pip install qdrant-client sentence-transformers")
        sys.exit(1)

    client = QdrantClient(host=args.host, port=args.port)
    model  = SentenceTransformer("all-MiniLM-L6-v2")

    # Ensure collection exists
    existing = [c.name for c in client.get_collections().collections]
    if args.collection not in existing:
        client.create_collection(
            collection_name=args.collection,
            vectors_config=VectorParams(size=384, distance=Distance.COSINE)
        )
        print(f"Created collection '{args.collection}'")

    docs_dir = Path(args.docs_dir)
    for json_file in docs_dir.glob("*.json"):
        with open(json_file) as f:
            doc = json.load(f)

        content = doc.get("content") or doc.get("body") or ""
        if not content:
            print(f"Skipping {json_file.name} — no content field")
            continue

        vector = model.encode(content).tolist()
        point  = PointStruct(
            id=str(uuid.uuid4()),
            vector=vector,
            payload={
                "documentId": doc.get("documentId") or doc.get("messageId", ""),
                "title":      doc.get("title", json_file.stem),
                "source":     str(json_file)
            }
        )
        client.upsert(collection_name=args.collection, points=[point])
        print(f"Stored: {json_file.name}")

    print("Seeding complete.")


if __name__ == "__main__":
    main()
