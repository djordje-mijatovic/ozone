/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.ozone.container.common.impl;

import com.google.common.base.Preconditions;
import org.apache.hadoop.hdds.scm.container.common.helpers
    .StorageContainerException;
import org.apache.hadoop.ozone.container.common.interfaces
    .ContainerDeletionChoosingPolicy;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.apache.hadoop.ozone.container.keyvalue.statemachine.background.BlockDeletingService.ContainerBlockInfo;

/**
 * TopN Ordered choosing policy that choosing containers based on pending
 * deletion blocks' number.
 */
public class TopNOrderedContainerDeletionChoosingPolicy
    implements ContainerDeletionChoosingPolicy {
  private static final Logger LOG =
      LoggerFactory.getLogger(TopNOrderedContainerDeletionChoosingPolicy.class);

  /** customized comparator used to compare differentiate container data. **/
  private static final Comparator<KeyValueContainerData>
        KEY_VALUE_CONTAINER_DATA_COMPARATOR = (KeyValueContainerData c1,
                                               KeyValueContainerData c2) ->
              Long.compare(c2.getNumPendingDeletionBlocks(),
                  c1.getNumPendingDeletionBlocks());

  @Override
  public List<ContainerBlockInfo> chooseContainerForBlockDeletion(
      int totalBlocks, Map<Long, ContainerData> candidateContainers)
      throws StorageContainerException {

    Preconditions.checkNotNull(candidateContainers,
        "Internal assertion: candidate containers cannot be null");

    List<ContainerBlockInfo> result = new ArrayList<>();
    List<KeyValueContainerData> orderedList = new LinkedList<>();
    for (ContainerData entry : candidateContainers.values()) {
      orderedList.add((KeyValueContainerData)entry);
    }
    Collections.sort(orderedList, KEY_VALUE_CONTAINER_DATA_COMPARATOR);

    // get top N list ordered by pending deletion blocks' number
    // Here we are returning containers based on totalBlocks which is basically
    // number of blocks to be deleted in an interval. We are also considering
    // the boundary case where the blocks of the last container exceeds the
    // number of blocks to be deleted in an interval, there we return that
    // container but with container we also return an integer so that total
    // blocks don't exceed the number of blocks to be deleted in an interval.

    for (KeyValueContainerData entry : orderedList) {
      long numBlocksToDelete =
          Math.min(totalBlocks, entry.getNumPendingDeletionBlocks());
      totalBlocks -= numBlocksToDelete;
      result.add(new ContainerBlockInfo(entry, numBlocksToDelete));
      if (LOG.isDebugEnabled()) {
        LOG.debug("Select container {} for block deletion, "
                + "pending deletion blocks num: {}.", entry.getContainerID(),
            entry.getNumPendingDeletionBlocks());
      }
      if (totalBlocks == 0) {
        break;
      }
    }
    return result;
  }
}
