/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.index;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.NormsProducer;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.codecs.TermVectorsReader;
import org.apache.lucene.search.Sort;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.InfoStream;
import org.apache.lucene.util.packed.PackedInts;
import org.apache.lucene.util.packed.PackedLongValues;

/** Holds common state used during segment merging.
 *
 * @lucene.experimental */
public class MergeState {

  /** Maps document IDs from old segments to document IDs in the new segment */
  public final DocMap[] docMaps;

  // nocommit can we somehow not need to expose this?  should IW's reader pool always sort on load...?
  public final DocMap[] leafDocMaps;

  /** {@link SegmentInfo} of the newly merged segment. */
  public final SegmentInfo segmentInfo;

  /** {@link FieldInfos} of the newly merged segment. */
  public FieldInfos mergeFieldInfos;

  /** Stored field producers being merged */
  public final StoredFieldsReader[] storedFieldsReaders;

  /** Term vector producers being merged */
  public final TermVectorsReader[] termVectorsReaders;

  /** Norms producers being merged */
  public final NormsProducer[] normsProducers;

  /** DocValues producers being merged */
  public final DocValuesProducer[] docValuesProducers;

  /** FieldInfos being merged */
  public final FieldInfos[] fieldInfos;

  /** Live docs for each reader */
  public final Bits[] liveDocs;

  /** Postings to merge */
  public final FieldsProducer[] fieldsProducers;

  /** Point readers to merge */
  public final PointsReader[] pointsReaders;

  /** Max docs per reader */
  public final int[] maxDocs;

  /** InfoStream for debugging messages. */
  public final InfoStream infoStream;

  /** Sole constructor. */
  MergeState(List<CodecReader> originalReaders, SegmentInfo segmentInfo, InfoStream infoStream) throws IOException {

    final Sort indexSort = segmentInfo.getIndexSort();
    int numReaders = originalReaders.size();
    leafDocMaps = new DocMap[numReaders];
    List<CodecReader> readers = maybeSortReaders(originalReaders, segmentInfo);

    maxDocs = new int[numReaders];
    fieldsProducers = new FieldsProducer[numReaders];
    normsProducers = new NormsProducer[numReaders];
    storedFieldsReaders = new StoredFieldsReader[numReaders];
    termVectorsReaders = new TermVectorsReader[numReaders];
    docValuesProducers = new DocValuesProducer[numReaders];
    pointsReaders = new PointsReader[numReaders];
    fieldInfos = new FieldInfos[numReaders];
    liveDocs = new Bits[numReaders];

    int numDocs = 0;
    for(int i=0;i<numReaders;i++) {
      final CodecReader reader = readers.get(i);

      maxDocs[i] = reader.maxDoc();
      liveDocs[i] = reader.getLiveDocs();
      fieldInfos[i] = reader.getFieldInfos();

      normsProducers[i] = reader.getNormsReader();
      if (normsProducers[i] != null) {
        normsProducers[i] = normsProducers[i].getMergeInstance();
      }
      
      docValuesProducers[i] = reader.getDocValuesReader();
      if (docValuesProducers[i] != null) {
        docValuesProducers[i] = docValuesProducers[i].getMergeInstance();
      }
      
      storedFieldsReaders[i] = reader.getFieldsReader();
      if (storedFieldsReaders[i] != null) {
        storedFieldsReaders[i] = storedFieldsReaders[i].getMergeInstance();
      }
      
      termVectorsReaders[i] = reader.getTermVectorsReader();
      if (termVectorsReaders[i] != null) {
        termVectorsReaders[i] = termVectorsReaders[i].getMergeInstance();
      }
      
      fieldsProducers[i] = reader.getPostingsReader().getMergeInstance();
      pointsReaders[i] = reader.getPointsReader();
      if (pointsReaders[i] != null) {
        pointsReaders[i] = pointsReaders[i].getMergeInstance();
      }
      numDocs += reader.numDocs();
    }

    segmentInfo.setMaxDoc(numDocs);

    this.segmentInfo = segmentInfo;
    this.infoStream = infoStream;
    this.docMaps = buildDocMaps(readers, indexSort);
  }

  private DocMap[] buildDocMaps(List<CodecReader> readers, Sort indexSort) throws IOException {

    int numReaders = readers.size();

    if (indexSort == null) {
      // no index sort ... we only must map around deletions, and rebase to the merged segment's docID space

      int totalDocs = 0;
      DocMap[] docMaps = new DocMap[numReaders];

      // Remap docIDs around deletions:
      for (int i = 0; i < numReaders; i++) {
        LeafReader reader = readers.get(i);
        Bits liveDocs = reader.getLiveDocs();

        final PackedLongValues delDocMap;
        if (liveDocs != null) {
          delDocMap = removeDeletes(reader.maxDoc(), liveDocs);
        } else {
          delDocMap = null;
        }

        final int docBase = totalDocs;
        docMaps[i] = new DocMap() {
          @Override
          public int get(int docID) {
            if (liveDocs == null) {
              return docBase + docID;
            } else if (liveDocs.get(docID)) {
              return docBase + (int) delDocMap.get(docID);
            } else {
              return -1;
            }
          }
        };
        totalDocs += reader.numDocs();
      }

      return docMaps;

    } else {
      // do a merge sort of the incoming leaves:
      return MultiSorter.sort(indexSort, readers);
    }
  }

  private List<CodecReader> maybeSortReaders(List<CodecReader> originalReaders, SegmentInfo segmentInfo) throws IOException {

    // Default to identity:
    for(int i=0;i<originalReaders.size();i++) {
      leafDocMaps[i] = new DocMap() {
          @Override
          public int get(int docID) {
            return docID;
          }
        };
    }

    Sort indexSort = segmentInfo.getIndexSort();
    if (indexSort == null) {
      return originalReaders;
    }

    // If an incoming reader is not sorted, because it was flushed by IW, we sort it here:
    final Sorter sorter = new Sorter(indexSort);
    List<CodecReader> readers = new ArrayList<>(originalReaders.size());

    //System.out.println("MergeState.maybeSortReaders indexSort=" + indexSort);

    for (CodecReader leaf : originalReaders) {
      Sort segmentSort = leaf.getIndexSort();
      //System.out.println("  leaf=" + leaf + " sort=" + segmentSort);

      if (segmentSort == null) {
        // TODO: fix IW to also sort when flushing?  It's somewhat tricky because of stored fields and term vectors, which write "live"
        // to the files on each indexed document:

        // This segment was written by flush, so documents are not yet sorted, so we sort them now:
        Sorter.DocMap sortDocMap = sorter.sort(leaf);
        if (sortDocMap != null) {
          //System.out.println("    sort!");
          leaf = SlowCodecReaderWrapper.wrap(SortingLeafReader.wrap(new MergeReaderWrapper(leaf), sortDocMap));
          leafDocMaps[readers.size()] = new DocMap() {
              @Override
              public int get(int docID) {
                return sortDocMap.oldToNew(docID);
              }
            };
        }

      } else if (segmentSort.equals(indexSort) == false) {
        throw new IllegalArgumentException("index sort mismatch: merged segment has sort=" + indexSort + " but to-be-merged segment has sort=" + segmentSort);
      }

      readers.add(leaf);
    }

    return readers;
  }

  /** A map of doc IDs. */
  public static abstract class DocMap {
    /** Return the mapped docID or -1 if the given doc is not mapped. */
    public abstract int get(int docID);
  }

  static PackedLongValues removeDeletes(final int maxDoc, final Bits liveDocs) {
    final PackedLongValues.Builder docMapBuilder = PackedLongValues.monotonicBuilder(PackedInts.COMPACT);
    int del = 0;
    for (int i = 0; i < maxDoc; ++i) {
      docMapBuilder.add(i - del);
      if (liveDocs.get(i) == false) {
        ++del;
      }
    }
    return docMapBuilder.build();
  }
}
