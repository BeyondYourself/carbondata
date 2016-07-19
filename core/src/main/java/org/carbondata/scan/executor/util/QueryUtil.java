/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.carbondata.scan.executor.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.carbondata.core.cache.Cache;
import org.carbondata.core.cache.CacheProvider;
import org.carbondata.core.cache.CacheType;
import org.carbondata.core.cache.dictionary.Dictionary;
import org.carbondata.core.cache.dictionary.DictionaryColumnUniqueIdentifier;
import org.carbondata.core.carbon.AbsoluteTableIdentifier;
import org.carbondata.core.carbon.CarbonTableIdentifier;
import org.carbondata.core.carbon.datastore.block.SegmentProperties;
import org.carbondata.core.carbon.metadata.CarbonMetadata;
import org.carbondata.core.carbon.metadata.datatype.DataType;
import org.carbondata.core.carbon.metadata.encoder.Encoding;
import org.carbondata.core.carbon.metadata.schema.table.CarbonTable;
import org.carbondata.core.carbon.metadata.schema.table.column.CarbonDimension;
import org.carbondata.core.carbon.metadata.schema.table.column.CarbonMeasure;
import org.carbondata.core.constants.CarbonCommonConstants;
import org.carbondata.core.keygenerator.KeyGenException;
import org.carbondata.core.keygenerator.KeyGenerator;
import org.carbondata.core.util.CarbonUtil;
import org.carbondata.core.util.CarbonUtilException;
import org.carbondata.query.complex.querytypes.ArrayQueryType;
import org.carbondata.query.complex.querytypes.PrimitiveQueryType;
import org.carbondata.query.complex.querytypes.StructQueryType;
import org.carbondata.scan.executor.exception.QueryExecutionException;
import org.carbondata.scan.executor.infos.KeyStructureInfo;
import org.carbondata.scan.expression.ColumnExpression;
import org.carbondata.scan.expression.Expression;
import org.carbondata.scan.expression.logical.BinaryLogicalExpression;
import org.carbondata.scan.filter.GenericQueryType;
import org.carbondata.scan.filter.resolver.FilterResolverIntf;
import org.carbondata.scan.filter.resolver.resolverinfo.DimColumnResolvedFilterInfo;
import org.carbondata.scan.model.QueryDimension;
import org.carbondata.scan.model.QueryMeasure;
import org.carbondata.scan.model.QueryModel;

import org.apache.commons.lang3.ArrayUtils;

/**
 * Utility class for query execution
 */
public class QueryUtil {

  /**
   * Below method will be used to get the masked byte range based on the query
   * dimension. It will give the range in the mdkey. This will be used to get
   * the actual key array from masked mdkey
   *
   * @param queryDimensions query dimension selected in query
   * @param keyGenerator    key generator
   * @return masked key
   */
  public static int[] getMaskedByteRange(List<QueryDimension> queryDimensions,
      KeyGenerator keyGenerator) {
    Set<Integer> byteRangeSet = new TreeSet<Integer>();
    int[] byteRange = null;
    for (int i = 0; i < queryDimensions.size(); i++) {

      // as no dictionary column and complex type columns
      // are not selected in the mdkey
      // so we will not select the those dimension for calculating the
      // range
      if (queryDimensions.get(i).getDimension().getKeyOrdinal() == -1) {
        continue;
      }
      // get the offset of the dimension in the mdkey
      byteRange =
          keyGenerator.getKeyByteOffsets(queryDimensions.get(i).getDimension().getKeyOrdinal());
      for (int j = byteRange[0]; j <= byteRange[1]; j++) {
        byteRangeSet.add(j);
      }
    }
    int[] maksedByteRange = new int[byteRangeSet.size()];
    int index = 0;
    Iterator<Integer> iterator = byteRangeSet.iterator();
    // add the masked byte range
    while (iterator.hasNext()) {
      maksedByteRange[index++] = iterator.next();
    }
    return maksedByteRange;
  }

  public static int[] getMaskedByteRangeBasedOrdinal(List<Integer> ordinals,
      KeyGenerator keyGenerator) {
    Set<Integer> byteRangeSet = new TreeSet<Integer>();
    int[] byteRange = null;
    for (int i = 0; i < ordinals.size(); i++) {

      // get the offset of the dimension in the mdkey
      byteRange = keyGenerator.getKeyByteOffsets(ordinals.get(i));
      for (int j = byteRange[0]; j <= byteRange[1]; j++) {
        byteRangeSet.add(j);
      }
    }
    int[] maksedByteRange = new int[byteRangeSet.size()];
    int index = 0;
    Iterator<Integer> iterator = byteRangeSet.iterator();
    // add the masked byte range
    while (iterator.hasNext()) {
      maksedByteRange[index++] = iterator.next();
    }
    return maksedByteRange;
  }

  /**
   * Below method will return the max key based on the dimension ordinal
   *
   * @param keyOrdinalList
   * @param generator
   * @return
   * @throws KeyGenException
   */
  public static byte[] getMaxKeyBasedOnOrinal(List<Integer> keyOrdinalList, KeyGenerator generator)
      throws KeyGenException {
    long[] max = new long[generator.getDimCount()];
    Arrays.fill(max, 0L);

    for (int i = 0; i < keyOrdinalList.size(); i++) {
      // adding for dimension which is selected in query
      max[keyOrdinalList.get(i)] = Long.MAX_VALUE;
    }
    return generator.generateKey(max);
  }

  /**
   * To get the max key based on dimensions. i.e. all other dimensions will be
   * set to 0 bits and the required query dimension will be masked with all
   * LONG.MAX so that we can mask key and then compare while aggregating This
   * can be useful during filter query when only few dimensions were selected
   * out of row group
   *
   * @param queryDimensions dimension selected in query
   * @param generator       key generator
   * @return max key for dimension
   * @throws KeyGenException if any problem while generating the key
   */
  public static byte[] getMaxKeyBasedOnDimensions(List<QueryDimension> queryDimensions,
      KeyGenerator generator) throws KeyGenException {
    long[] max = new long[generator.getDimCount()];
    Arrays.fill(max, 0L);

    for (int i = 0; i < queryDimensions.size(); i++) {
      // as no dictionary column and complex type columns
      // are not selected in the mdkey
      // so we will not select the those dimension for calculating the
      // range
      if (queryDimensions.get(i).getDimension().getKeyOrdinal() == -1) {
        continue;
      }
      // adding for dimension which is selected in query
      max[queryDimensions.get(i).getDimension().getKeyOrdinal()] = Long.MAX_VALUE;
    }

    return generator.generateKey(max);
  }

  /**
   * Below method will be used to get the masked key for query
   *
   * @param keySize         size of the masked key
   * @param maskedKeyRanges masked byte range
   * @return masked bytes
   */
  public static int[] getMaskedByte(int keySize, int[] maskedKeyRanges) {
    int[] maskedKey = new int[keySize];
    // all the non selected dimension will be filled with -1
    Arrays.fill(maskedKey, -1);
    for (int i = 0; i < maskedKeyRanges.length; i++) {
      maskedKey[maskedKeyRanges[i]] = i;
    }
    return maskedKey;
  }

  /**
   * Below method will be used to get the dimension block index in file based
   * on query dimension
   *
   * @param queryDimensions                query dimension
   * @param dimensionOrdinalToBlockMapping mapping of dimension block in file to query dimension
   * @return block index of file
   */
  public static int[] getDimensionsBlockIndexes(List<QueryDimension> queryDimensions,
      Map<Integer, Integer> dimensionOrdinalToBlockMapping,
      List<CarbonDimension> customAggregationDimension) {
    // using set as in row group columns will point to same block
    Set<Integer> dimensionBlockIndex = new HashSet<Integer>();
    int blockIndex = 0;
    for (int i = 0; i < queryDimensions.size(); i++) {
      blockIndex =
          dimensionOrdinalToBlockMapping.get(queryDimensions.get(i).getDimension().getOrdinal());
      dimensionBlockIndex.add(blockIndex);
      addChildrenBlockIndex(blockIndex, dimensionBlockIndex, queryDimensions.get(i).getDimension());
    }
    for (int i = 0; i < customAggregationDimension.size(); i++) {
      blockIndex =
          dimensionOrdinalToBlockMapping.get(customAggregationDimension.get(i).getOrdinal());
      dimensionBlockIndex.add(blockIndex);
      addChildrenBlockIndex(blockIndex, dimensionBlockIndex, customAggregationDimension.get(i));
    }
    return ArrayUtils
        .toPrimitive(dimensionBlockIndex.toArray(new Integer[dimensionBlockIndex.size()]));
  }

  /**
   * Below method will be used to add the children block index
   * this will be basically for complex dimension which will have children
   *
   * @param startBlockIndex start block index
   * @param blockIndexList  block index list
   * @param dimension       parent dimension
   */
  private static void addChildrenBlockIndex(int startBlockIndex, Set<Integer> blockIndexList,
      CarbonDimension dimension) {
    for (int i = 0; i < dimension.numberOfChild(); i++) {
      blockIndexList.add(++startBlockIndex);
      addChildrenBlockIndex(startBlockIndex, blockIndexList,
          dimension.getListOfChildDimensions().get(i));
    }
  }

  /**
   * Below method will be used to get the dictionary mapping for all the
   * dictionary encoded dimension present in the query
   *
   * @param queryDimensions            query dimension present in the query this will be used to
   *                                   convert the result from surrogate key to actual data
   * @param absoluteTableIdentifier    absolute table identifier
   * @return dimension unique id to its dictionary map
   * @throws QueryExecutionException
   */
  public static Map<String, Dictionary> getDimensionDictionaryDetail(
      List<QueryDimension> queryDimensions,
      Set<CarbonDimension> filterComplexDimensions,
      AbsoluteTableIdentifier absoluteTableIdentifier) throws QueryExecutionException {
    // to store dimension unique column id list, this is required as
    // dimension can be present in
    // query dimension, as well as some aggregation function will be applied
    // in the same dimension
    // so we need to get only one instance of dictionary
    // direct dictionary skip is done only for the dictionary lookup
    Set<String> dictionaryDimensionFromQuery = new HashSet<String>();
    for (int i = 0; i < queryDimensions.size(); i++) {
      List<Encoding> encodingList = queryDimensions.get(i).getDimension().getEncoder();
      // TODO need to remove the data type check for parent column in complex type no need to
      // write encoding dictionary
      if (CarbonUtil.hasEncoding(encodingList, Encoding.DICTIONARY) && !CarbonUtil
          .hasEncoding(encodingList, Encoding.DIRECT_DICTIONARY)) {

        if (queryDimensions.get(i).getDimension().numberOfChild() == 0) {
          dictionaryDimensionFromQuery.add(queryDimensions.get(i).getDimension().getColumnId());
        }
        if (queryDimensions.get(i).getDimension().numberOfChild() > 0) {
          getChildDimensionDictionaryDetail(queryDimensions.get(i).getDimension(),
              dictionaryDimensionFromQuery);
        }
      }
    }
    Iterator<CarbonDimension> iterator = filterComplexDimensions.iterator();
    while (iterator.hasNext()) {
      getChildDimensionDictionaryDetail(iterator.next(), dictionaryDimensionFromQuery);
    }
    // converting to list as api exposed needed list which i think
    // is not correct
    List<String> dictionaryColumnIdList =
        new ArrayList<String>(dictionaryDimensionFromQuery.size());
    dictionaryColumnIdList.addAll(dictionaryDimensionFromQuery);
    return getDictionaryMap(dictionaryColumnIdList, absoluteTableIdentifier);
  }

  /**
   * Below method will be used to fill the children dimension column id
   *
   * @param queryDimensions              query dimension
   * @param dictionaryDimensionFromQuery dictionary dimension for query
   */
  private static void getChildDimensionDictionaryDetail(CarbonDimension queryDimensions,
      Set<String> dictionaryDimensionFromQuery) {
    for (int j = 0; j < queryDimensions.numberOfChild(); j++) {
      List<Encoding> encodingList = queryDimensions.getListOfChildDimensions().get(j).getEncoder();
      if (queryDimensions.getListOfChildDimensions().get(j).numberOfChild() > 0) {
        getChildDimensionDictionaryDetail(queryDimensions.getListOfChildDimensions().get(j),
            dictionaryDimensionFromQuery);
      } else if(!CarbonUtil.hasEncoding(encodingList, Encoding.DIRECT_DICTIONARY)) {
        dictionaryDimensionFromQuery
            .add(queryDimensions.getListOfChildDimensions().get(j).getColumnId());
      }
    }
  }

  /**
   * Below method will be used to get the column id to its dictionary mapping
   *
   * @param dictionaryColumnIdList  dictionary column list
   * @param absoluteTableIdentifier absolute table identifier
   * @return dictionary mapping
   * @throws QueryExecutionException
   */
  private static Map<String, Dictionary> getDictionaryMap(List<String> dictionaryColumnIdList,
      AbsoluteTableIdentifier absoluteTableIdentifier) throws QueryExecutionException {
    // this for dictionary unique identifier
    List<DictionaryColumnUniqueIdentifier> dictionaryColumnUniqueIdentifiers =
        getDictionaryColumnUniqueIdentifierList(dictionaryColumnIdList,
            absoluteTableIdentifier.getCarbonTableIdentifier());
    CacheProvider cacheProvider = CacheProvider.getInstance();
    Cache forwardDictionaryCache = cacheProvider
        .createCache(CacheType.FORWARD_DICTIONARY, absoluteTableIdentifier.getStorePath());
    List<Dictionary> columnDictionaryList = null;
    try {
      columnDictionaryList = forwardDictionaryCache.getAll(dictionaryColumnUniqueIdentifiers);
    } catch (CarbonUtilException e) {
      throw new QueryExecutionException(e);
    }
    Map<String, Dictionary> columnDictionaryMap = new HashMap<>(columnDictionaryList.size());
    for (int i = 0; i < dictionaryColumnUniqueIdentifiers.size(); i++) {
      // TODO: null check for column dictionary, if cache size is less it
      // might return null here, in that case throw exception
      columnDictionaryMap.put(dictionaryColumnIdList.get(i), columnDictionaryList.get(i));
    }
    return columnDictionaryMap;
  }

  /**
   * Below method will be used to get the dictionary column unique identifier
   *
   * @param dictionaryColumnIdList dictionary
   * @param carbonTableIdentifier
   * @return
   */
  private static List<DictionaryColumnUniqueIdentifier> getDictionaryColumnUniqueIdentifierList(
      List<String> dictionaryColumnIdList, CarbonTableIdentifier carbonTableIdentifier)
      throws QueryExecutionException {
    CarbonTable carbonTable =
        CarbonMetadata.getInstance().getCarbonTable(carbonTableIdentifier.getTableUniqueName());
    List<DictionaryColumnUniqueIdentifier> dictionaryColumnUniqueIdentifiers =
        new ArrayList<>(dictionaryColumnIdList.size());
    for (String columnId : dictionaryColumnIdList) {
      CarbonDimension dimension = CarbonMetadata.getInstance()
          .getCarbonDimensionBasedOnColIdentifier(carbonTable, columnId);
      if (null == dimension) {
        throw new QueryExecutionException("The column id " + columnId + " could not be resolved.");
      }
      DictionaryColumnUniqueIdentifier dictionaryColumnUniqueIdentifier =
          new DictionaryColumnUniqueIdentifier(carbonTableIdentifier,
              dimension.getColumnIdentifier(), dimension.getDataType());
      dictionaryColumnUniqueIdentifiers.add(dictionaryColumnUniqueIdentifier);
    }
    return dictionaryColumnUniqueIdentifiers;
  }

  /**
   * Below method will used to get the method will be used to get the measure
   * block indexes to be read from the file
   *
   * @param queryMeasures              query measure
   * @param expressionMeasure          measure present in the expression
   * @param ordinalToBlockIndexMapping measure ordinal to block mapping
   * @return block indexes
   */
  public static int[] getMeasureBlockIndexes(List<QueryMeasure> queryMeasures,
      List<CarbonMeasure> expressionMeasure, Map<Integer, Integer> ordinalToBlockIndexMapping) {
    Set<Integer> measureBlockIndex = new HashSet<Integer>();
    for (int i = 0; i < queryMeasures.size(); i++) {
      measureBlockIndex
          .add(ordinalToBlockIndexMapping.get(queryMeasures.get(i).getMeasure().getOrdinal()));
    }
    for (int i = 0; i < expressionMeasure.size(); i++) {
      measureBlockIndex.add(ordinalToBlockIndexMapping.get(expressionMeasure.get(i).getOrdinal()));
    }
    return ArrayUtils.toPrimitive(measureBlockIndex.toArray(new Integer[measureBlockIndex.size()]));
  }

  /**
   * Below method will be used to get the masked byte range for dimension
   * which is present in order by
   *
   * @param orderByDimensions order by dimension
   * @param generator         key generator
   * @param maskedRanges      masked byte range for dimension
   * @return range of masked byte for order by dimension
   */
  public static int[][] getMaskedByteRangeForSorting(List<QueryDimension> orderByDimensions,
      KeyGenerator generator, int[] maskedRanges) {
    int[][] dimensionCompareIndex = new int[orderByDimensions.size()][];
    int index = 0;
    for (int i = 0; i < dimensionCompareIndex.length; i++) {
      Set<Integer> integers = new TreeSet<Integer>();
      if (!orderByDimensions.get(i).getDimension().getEncoder().contains(Encoding.DICTIONARY)
          || orderByDimensions.get(i).getDimension().numberOfChild() > 0) {
        continue;
      }
      int[] range =
          generator.getKeyByteOffsets(orderByDimensions.get(i).getDimension().getKeyOrdinal());
      for (int j = range[0]; j <= range[1]; j++) {
        integers.add(j);
      }
      dimensionCompareIndex[index] = new int[integers.size()];
      int j = 0;
      for (Iterator<Integer> iterator = integers.iterator(); iterator.hasNext(); ) {
        Integer integer = (Integer) iterator.next();
        dimensionCompareIndex[index][j++] = integer.intValue();
      }
      index++;
    }
    for (int i = 0; i < dimensionCompareIndex.length; i++) {
      if (null == dimensionCompareIndex[i]) {
        continue;
      }
      int[] range = dimensionCompareIndex[i];
      if (null != range) {
        for (int j = 0; j < range.length; j++) {
          for (int k = 0; k < maskedRanges.length; k++) {
            if (range[j] == maskedRanges[k]) {
              range[j] = k;
              break;
            }
          }
        }
      }

    }
    return dimensionCompareIndex;
  }

  /**
   * Below method will be used to get the masked key for sorting
   *
   * @param orderDimensions           query dimension
   * @param generator                 key generator
   * @param maskedByteRangeForSorting masked byte range for sorting
   * @param maskedRanges              masked range
   * @return masked byte range
   * @throws QueryExecutionException
   */
  public static byte[][] getMaksedKeyForSorting(List<QueryDimension> orderDimensions,
      KeyGenerator generator, int[][] maskedByteRangeForSorting, int[] maskedRanges)
      throws QueryExecutionException {
    byte[][] maskedKey = new byte[orderDimensions.size()][];
    byte[] mdKey = null;
    long[] key = null;
    byte[] maskedMdKey = null;
    try {
      if (null != maskedByteRangeForSorting) {
        for (int i = 0; i < maskedByteRangeForSorting.length; i++) {
          if (null == maskedByteRangeForSorting[i]) {
            continue;
          }
          key = new long[generator.getDimCount()];
          maskedKey[i] = new byte[maskedByteRangeForSorting[i].length];
          key[orderDimensions.get(i).getDimension().getKeyOrdinal()] = Long.MAX_VALUE;
          mdKey = generator.generateKey(key);
          maskedMdKey = new byte[maskedRanges.length];
          for (int k = 0; k < maskedMdKey.length; k++) { // CHECKSTYLE:OFF
            // Approval
            // No:Approval-V1R2C10_001
            maskedMdKey[k] = mdKey[maskedRanges[k]];
          }
          for (int j = 0; j < maskedByteRangeForSorting[i].length; j++) {
            maskedKey[i][j] = maskedMdKey[maskedByteRangeForSorting[i][j]];
          }// CHECKSTYLE:ON

        }
      }
    } catch (KeyGenException e) {
      throw new QueryExecutionException(e);
    }
    return maskedKey;
  }

  /**
   * Below method will be used to get mapping whether dimension is present in
   * order by or not
   *
   * @param sortedDimensions sort dimension present in order by query
   * @param queryDimensions  query dimension
   * @return sort dimension indexes
   */
  public static byte[] getSortDimensionIndexes(List<QueryDimension> sortedDimensions,
      List<QueryDimension> queryDimensions) {
    byte[] sortedDims = new byte[queryDimensions.size()];
    int indexOf = 0;
    for (int i = 0; i < sortedDims.length; i++) {
      indexOf = sortedDimensions.indexOf(queryDimensions.get(i));
      if (indexOf > -1) {
        sortedDims[i] = 1;
      }
    }
    return sortedDims;
  }

  /**
   * Below method will be used to get the mapping of block index and its
   * restructuring info
   *
   * @param queryDimensions   query dimension from query model
   * @param segmentProperties segment properties
   * @return map of block index to its restructuring info
   * @throws KeyGenException if problem while key generation
   */
  public static Map<Integer, KeyStructureInfo> getColumnGroupKeyStructureInfo(
      List<QueryDimension> queryDimensions, SegmentProperties segmentProperties)
      throws KeyGenException {
    Map<Integer, KeyStructureInfo> rowGroupToItsRSInfo = new HashMap<Integer, KeyStructureInfo>();
    // get column group id and its ordinal mapping of column group
    Map<Integer, List<Integer>> columnGroupAndItsOrdinalMappingForQuery =
        getColumnGroupAndItsOrdinalMapping(queryDimensions);
    Map<Integer, KeyGenerator> columnGroupAndItsKeygenartor =
        segmentProperties.getColumnGroupAndItsKeygenartor();

    Iterator<Entry<Integer, List<Integer>>> iterator =
        columnGroupAndItsOrdinalMappingForQuery.entrySet().iterator();
    KeyStructureInfo restructureInfos = null;
    while (iterator.hasNext()) {
      Entry<Integer, List<Integer>> next = iterator.next();
      KeyGenerator keyGenerator = columnGroupAndItsKeygenartor.get(next.getKey());
      restructureInfos = new KeyStructureInfo();
      // sort the ordinal
      List<Integer> ordinal = next.getValue();
      List<Integer> mdKeyOrdinal = new ArrayList<Integer>();
      for (Integer ord : ordinal) {
        mdKeyOrdinal.add(segmentProperties.getColumnGroupMdKeyOrdinal(next.getKey(), ord));
      }
      Collections.sort(mdKeyOrdinal);
      // get the masked byte range for column group
      int[] maskByteRanges = getMaskedByteRangeBasedOrdinal(mdKeyOrdinal, keyGenerator);
      // max key for column group
      byte[] maxKey = getMaxKeyBasedOnOrinal(mdKeyOrdinal, keyGenerator);
      // get masked key for column group
      int[] maksedByte = getMaskedByte(keyGenerator.getKeySizeInBytes(), maskByteRanges);
      restructureInfos.setKeyGenerator(keyGenerator);
      restructureInfos.setMaskByteRanges(maskByteRanges);
      restructureInfos.setMaxKey(maxKey);
      restructureInfos.setMaskedBytes(maksedByte);
      rowGroupToItsRSInfo
          .put(segmentProperties.getDimensionOrdinalToBlockMapping().get(ordinal.get(0)),
              restructureInfos);
    }
    return rowGroupToItsRSInfo;
  }

  /**
   * return true if given key is found in array
   *
   * @param data
   * @param key
   * @return
   */
  public static boolean searchInArray(int[] data, int key) {
    for (int i = 0; i < data.length; i++) {
      if (key == data[i]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Below method will be used to create a mapping of column group columns
   * this mapping will have column group id to all the dimension ordinal
   * present in the column group This mapping will be used during query
   * execution, to create a mask key for the column group dimension which will
   * be used in aggregation and filter query as column group dimension will be
   * stored in bit level
   */
  private static Map<Integer, List<Integer>> getColumnGroupAndItsOrdinalMapping(
      List<QueryDimension> origdimensions) {

    List<QueryDimension> dimensions = new ArrayList<QueryDimension>(origdimensions.size());
    dimensions.addAll(origdimensions);
    /**
     * sort based on column group id
     */
    Collections.sort(dimensions, new Comparator<QueryDimension>() {

      @Override public int compare(QueryDimension o1, QueryDimension o2) {
        return Integer
            .compare(o1.getDimension().columnGroupId(), o2.getDimension().columnGroupId());
      }
    });
    // list of row groups this will store all the row group column
    Map<Integer, List<Integer>> columnGroupAndItsOrdinalsMapping =
        new HashMap<Integer, List<Integer>>();
    // to store a column group
    List<Integer> currentColumnGroup = null;
    // current index
    int index = 0;
    // previous column group to check all the column of row id has bee
    // selected
    int prvColumnGroupId = -1;
    while (index < dimensions.size()) {
      // if dimension group id is not zero and it is same as the previous
      // column group id
      // then we need to add ordinal of that column as it belongs to same
      // column group
      if (!dimensions.get(index).getDimension().isColumnar()
          && dimensions.get(index).getDimension().columnGroupId() == prvColumnGroupId
          && null != currentColumnGroup) {
        currentColumnGroup.add(dimensions.get(index).getDimension().getOrdinal());
      }

      // if dimension is not a columnar then it is column group column
      else if (!dimensions.get(index).getDimension().isColumnar()) {
        currentColumnGroup = new ArrayList<Integer>();
        columnGroupAndItsOrdinalsMapping
            .put(dimensions.get(index).getDimension().columnGroupId(), currentColumnGroup);
        currentColumnGroup.add(dimensions.get(index).getDimension().getOrdinal());
      }
      // update the row id every time,this is required to group the
      // columns
      // of the same row group
      prvColumnGroupId = dimensions.get(index).getDimension().columnGroupId();
      index++;
    }
    return columnGroupAndItsOrdinalsMapping;
  }

  /**
   * Below method will be used to get masked byte
   *
   * @param data           actual data
   * @param maxKey         max key
   * @param maskByteRanges mask byte range
   * @param byteCount
   * @return masked byte
   */
  public static byte[] getMaskedKey(byte[] data, byte[] maxKey, int[] maskByteRanges,
      int byteCount) {
    byte[] maskedKey = new byte[byteCount];
    int counter = 0;
    int byteRange = 0;
    for (int i = 0; i < byteCount; i++) {
      byteRange = maskByteRanges[i];
      if (byteRange != -1) {
        maskedKey[counter++] = (byte) (data[byteRange] & maxKey[byteRange]);
      }
    }
    return maskedKey;
  }

  /**
   * Below method will be used to fill block indexes of the query dimension
   * which will be used in creating a output row Here is method we are passing
   * two list which store the indexes one for dictionary column other for not
   * dictionary column. This is done for specific purpose so that in one
   * iteration we will be able to fill both type dimension block indexes
   *
   * @param queryDimensions                  dimension present in the query
   * @param columnOrdinalToBlockIndexMapping column ordinal to block index mapping
   * @param dictionaryDimensionBlockIndex    list to store dictionary column block indexes
   * @param noDictionaryDimensionBlockIndex  list to store no dictionary block indexes
   */
  public static void fillQueryDimensionsBlockIndexes(List<QueryDimension> queryDimensions,
      Map<Integer, Integer> columnOrdinalToBlockIndexMapping,
      Set<Integer> dictionaryDimensionBlockIndex, List<Integer> noDictionaryDimensionBlockIndex) {
    for (QueryDimension queryDimension : queryDimensions) {
      if (CarbonUtil.hasEncoding(queryDimension.getDimension().getEncoder(), Encoding.DICTIONARY)
          && queryDimension.getDimension().numberOfChild() == 0) {
        dictionaryDimensionBlockIndex
            .add(columnOrdinalToBlockIndexMapping.get(queryDimension.getDimension().getOrdinal()));
      } else if(queryDimension.getDimension().numberOfChild() == 0){
        noDictionaryDimensionBlockIndex
            .add(columnOrdinalToBlockIndexMapping.get(queryDimension.getDimension().getOrdinal()));
      }
    }
  }

  /**
   * Below method will be used to resolve the query model
   * resolve will be setting the actual dimension and measure object
   * as from driver only column name will be passes to avoid the heavy object
   * serialization
   *
   * @param queryModel query model
   */
  public static void resolveQueryModel(QueryModel queryModel) {
    CarbonMetadata.getInstance().addCarbonTable(queryModel.getTable());
    // TODO need to load the table from table identifier
    CarbonTable carbonTable = queryModel.getTable();
    String tableName =
        queryModel.getAbsoluteTableIdentifier().getCarbonTableIdentifier().getTableName();
    // resolve query dimension
    for (QueryDimension queryDimension : queryModel.getQueryDimension()) {
      queryDimension
          .setDimension(carbonTable.getDimensionByName(tableName, queryDimension.getColumnName()));
    }
    // resolve sort dimension
    for (QueryDimension sortDimension : queryModel.getSortDimension()) {
      sortDimension
          .setDimension(carbonTable.getDimensionByName(tableName, sortDimension.getColumnName()));
    }
    // resolve query measure
    for (QueryMeasure queryMeasure : queryModel.getQueryMeasures()) {
      // in case of count start column name will  be count * so
      // first need to check any measure is present or not and as if measure
      // if measure is present and if first measure is not a default
      // measure than add measure otherwise
      // than add first dimension as a measure
      //as currently if measure is not present then
      //we are adding default measure so first condition will
      //never come false but if in future we can remove so not removing first if check
      if (queryMeasure.getColumnName().equals("count(*)")) {
        if (carbonTable.getMeasureByTableName(tableName).size() > 0 && !carbonTable
            .getMeasureByTableName(tableName).get(0).getColName()
            .equals(CarbonCommonConstants.DEFAULT_INVISIBLE_DUMMY_MEASURE)) {
          queryMeasure.setMeasure(carbonTable.getMeasureByTableName(tableName).get(0));
        } else {
          CarbonMeasure dummyMeasure = new CarbonMeasure(
              carbonTable.getDimensionByTableName(tableName).get(0).getColumnSchema(), 0);
          queryMeasure.setMeasure(dummyMeasure);
        }
      } else {
        queryMeasure
            .setMeasure(carbonTable.getMeasureByName(tableName, queryMeasure.getColumnName()));
      }
    }
  }

  /**
   * Below method will be used to get the index of number type aggregator
   *
   * @param aggType
   * @return index in aggregator
   */
  public static int[] getNumberTypeIndex(List<String> aggType) {
    List<Integer> indexList = new ArrayList<Integer>();
    for (int i = 0; i < aggType.size(); i++) {
      if (CarbonCommonConstants.SUM.equals(aggType.get(i)) || CarbonCommonConstants.AVERAGE
          .equals(aggType.get(i))) {
        indexList.add(i);
      }
    }
    return ArrayUtils.toPrimitive(indexList.toArray(new Integer[indexList.size()]));
  }

  /**
   * below method will be used to get the actual type aggregator
   *
   * @param aggType
   * @return index in aggrgetor
   */
  public static int[] getActualTypeIndex(List<String> aggType) {
    List<Integer> indexList = new ArrayList<Integer>();
    for (int i = 0; i < aggType.size(); i++) {
      if (!CarbonCommonConstants.SUM.equals(aggType.get(i)) && !CarbonCommonConstants.AVERAGE
          .equals(aggType.get(i))) {
        indexList.add(i);
      }
    }
    return ArrayUtils.toPrimitive(indexList.toArray(new Integer[indexList.size()]));
  }

  /**
   * Below method will be used to get the key structure for the column group
   *
   * @param segmentProperties      segment properties
   * @param dimColumnEvaluatorInfo dimension evaluator info
   * @return key structure info for column group dimension
   * @throws KeyGenException
   */
  public static KeyStructureInfo getKeyStructureInfo(SegmentProperties segmentProperties,
      DimColumnResolvedFilterInfo dimColumnEvaluatorInfo) throws KeyGenException {
    int colGrpId = getColumnGroupId(segmentProperties, dimColumnEvaluatorInfo.getColumnIndex());
    KeyGenerator keyGenerator = segmentProperties.getColumnGroupAndItsKeygenartor().get(colGrpId);
    List<Integer> mdKeyOrdinal = new ArrayList<Integer>();

    mdKeyOrdinal.add(segmentProperties
        .getColumnGroupMdKeyOrdinal(colGrpId, dimColumnEvaluatorInfo.getColumnIndex()));
    int[] maskByteRanges = QueryUtil.getMaskedByteRangeBasedOrdinal(mdKeyOrdinal, keyGenerator);
    byte[] maxKey = QueryUtil.getMaxKeyBasedOnOrinal(mdKeyOrdinal, keyGenerator);
    int[] maksedByte = QueryUtil.getMaskedByte(keyGenerator.getKeySizeInBytes(), maskByteRanges);
    KeyStructureInfo restructureInfos = new KeyStructureInfo();
    restructureInfos.setKeyGenerator(keyGenerator);
    restructureInfos.setMaskByteRanges(maskByteRanges);
    restructureInfos.setMaxKey(maxKey);
    restructureInfos.setMaskedBytes(maksedByte);
    return restructureInfos;
  }

  /**
   * Below method will be used to get the column group id based on the ordinal
   *
   * @param segmentProperties segment properties
   * @param ordinal           ordinal to be searched
   * @return column group id
   */
  public static int getColumnGroupId(SegmentProperties segmentProperties, int ordinal) {
    int[][] columnGroups = segmentProperties.getColumnGroups();
    int colGrpId = -1;
    for (int i = 0; i < columnGroups.length; i++) {
      if (columnGroups[i].length > 1) {
        colGrpId++;
        if (QueryUtil.searchInArray(columnGroups[i], ordinal)) {
          break;
        }
      }
    }
    return colGrpId;
  }

  /**
   * Below method will be used to get the map of for complex dimension and its type
   * which will be used to during query execution to
   *
   * @param queryDimensions          complex dimension in query
   * @param dimensionToBlockIndexMap dimension to block index in file map
   * @return complex dimension and query type
   */
  public static Map<Integer, GenericQueryType> getComplexDimensionsMap(
      List<QueryDimension> queryDimensions, Map<Integer, Integer> dimensionToBlockIndexMap,
      int[] eachComplexColumnValueSize, Map<String, Dictionary> columnIdToDictionaryMap,
      Set<CarbonDimension> filterDimensions) {
    Map<Integer, GenericQueryType> complexTypeMap = new HashMap<Integer, GenericQueryType>();
    for (QueryDimension dimension : queryDimensions) {
      CarbonDimension actualDimension = dimension.getDimension();
      if (actualDimension.getNumberOfChild() == 0) {
        continue;
      }
      fillParentDetails(dimensionToBlockIndexMap, actualDimension, complexTypeMap,
          eachComplexColumnValueSize, columnIdToDictionaryMap);
    }
    if (null != filterDimensions) {
      for (CarbonDimension filterDimension : filterDimensions) {
        fillParentDetails(dimensionToBlockIndexMap, filterDimension, complexTypeMap,
            eachComplexColumnValueSize, columnIdToDictionaryMap);
      }
    }
    return complexTypeMap;
  }

  private static GenericQueryType fillParentDetails(Map<Integer, Integer> dimensionToBlockIndexMap,
      CarbonDimension dimension, Map<Integer, GenericQueryType> complexTypeMap,
      int[] eachComplexColumnValueSize, Map<String, Dictionary> columnIdToDictionaryMap) {
    int parentBlockIndex = dimensionToBlockIndexMap.get(dimension.getOrdinal());
    GenericQueryType parentQueryType = dimension.getDataType().equals(DataType.ARRAY) ?
        new ArrayQueryType(dimension.getColName(), dimension.getColName(), parentBlockIndex) :
        new StructQueryType(dimension.getColName(), dimension.getColName(),
            dimensionToBlockIndexMap.get(dimension.getOrdinal()));
    complexTypeMap.put(dimension.getOrdinal(), parentQueryType);
    parentBlockIndex =
        fillChildrenDetails(eachComplexColumnValueSize, columnIdToDictionaryMap, parentBlockIndex,
            dimension, parentQueryType);
    return parentQueryType;
  }

  private static int fillChildrenDetails(int[] eachComplexColumnValueSize,
      Map<String, Dictionary> columnIdToDictionaryMap, int parentBlockIndex,
      CarbonDimension dimension, GenericQueryType parentQueryType) {
    for (int i = 0; i < dimension.getNumberOfChild(); i++) {
      switch (dimension.getListOfChildDimensions().get(i).getDataType()) {
        case ARRAY:
          parentQueryType.addChildren(
              new ArrayQueryType(dimension.getListOfChildDimensions().get(i).getColName(),
                  dimension.getColName(), ++parentBlockIndex));
          break;
        case STRUCT:
          parentQueryType.addChildren(
              new StructQueryType(dimension.getListOfChildDimensions().get(i).getColName(),
                  dimension.getColName(), ++parentBlockIndex));
          break;
        default:
          boolean isDirectDictionary = CarbonUtil.hasEncoding(
              dimension.getListOfChildDimensions().get(i).getEncoder(),
              Encoding.DIRECT_DICTIONARY);
          parentQueryType.addChildren(
              new PrimitiveQueryType(dimension.getListOfChildDimensions().get(i).getColName(),
                  dimension.getColName(), ++parentBlockIndex,
                  dimension.getListOfChildDimensions().get(i).getDataType(),
                  eachComplexColumnValueSize[dimension.getListOfChildDimensions().get(i)
                      .getComplexTypeOrdinal()], columnIdToDictionaryMap
                  .get(dimension.getListOfChildDimensions().get(i).getColumnId()),
                  isDirectDictionary));
      }
      if (dimension.getListOfChildDimensions().get(i).getNumberOfChild() > 0) {
        parentBlockIndex = fillChildrenDetails(eachComplexColumnValueSize, columnIdToDictionaryMap,
            parentBlockIndex, dimension.getListOfChildDimensions().get(i), parentQueryType);
      }
    }
    return parentBlockIndex;
  }

  public static Set<CarbonDimension> getAllFilterDimensions(FilterResolverIntf filterResolverTree) {
    Set<CarbonDimension> filterDimensions = new HashSet<CarbonDimension>();
    if (null == filterResolverTree) {
      return filterDimensions;
    }
    List<ColumnExpression> dimensionResolvedInfos = new ArrayList<ColumnExpression>();
    Expression filterExpression = filterResolverTree.getFilterExpression();
    if (filterExpression instanceof BinaryLogicalExpression) {
      BinaryLogicalExpression logicalExpression = (BinaryLogicalExpression) filterExpression;
      dimensionResolvedInfos.addAll(logicalExpression.getColumnList());
    }
    for (ColumnExpression info : dimensionResolvedInfos) {
      if (info.isDimension() && info.getDimension().getNumberOfChild() > 0) {
        filterDimensions.add(info.getDimension());
      }
    }
    return filterDimensions;

  }

}
