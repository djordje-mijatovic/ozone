/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.recon.recovery;

import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_OM_SNAPSHOT_DB;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_OM_SNAPSHOT_DB_DIR;

import java.io.File;
import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.utils.db.RDBStore;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.DBStoreBuilder;
import org.apache.hadoop.ozone.recon.ReconUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recon's implementation of the OM Metadata manager. By extending and
 * relying on the OmMetadataManagerImpl, we can make sure all changes made to
 * schema in OM will be automatically picked up by Recon.
 */
@Singleton
public class ReconOmMetadataManagerImpl extends OmMetadataManagerImpl
    implements ReconOMMetadataManager {

  private static final Logger LOG =
      LoggerFactory.getLogger(ReconOmMetadataManagerImpl.class);

  private OzoneConfiguration ozoneConfiguration;
  private ReconUtils reconUtils;
  private boolean omTablesInitialized = false;

  @Inject
  public ReconOmMetadataManagerImpl(OzoneConfiguration configuration,
                                    ReconUtils reconUtils) {
    this.reconUtils = reconUtils;
    this.ozoneConfiguration = configuration;
  }

  @Override
  public void start(OzoneConfiguration configuration) throws IOException {
    LOG.info("Starting ReconOMMetadataManagerImpl");
    File reconDbDir =
        reconUtils.getReconDbDir(configuration, OZONE_RECON_OM_SNAPSHOT_DB_DIR);
    File lastKnownOMSnapshot =
        reconUtils.getLastKnownDB(reconDbDir, RECON_OM_SNAPSHOT_DB);
    if (lastKnownOMSnapshot != null) {
      LOG.info("Last known snapshot for OM : {}",
          lastKnownOMSnapshot.getAbsolutePath());
      initializeNewRdbStore(lastKnownOMSnapshot);
    }
  }

  /**
   * Replace existing DB instance with new one.
   *
   * @param dbFile new DB file location.
   */
  private void initializeNewRdbStore(File dbFile) throws IOException {
    try {
      DBStoreBuilder dbStoreBuilder =
          DBStoreBuilder.newBuilder(ozoneConfiguration)
          .setName(dbFile.getName())
          .setPath(dbFile.toPath().getParent());
      addOMTablesAndCodecs(dbStoreBuilder);
      DBStore newStore = dbStoreBuilder.build();
      setStore(newStore);
      LOG.info("Created OM DB handle from snapshot at {}.",
          dbFile.getAbsolutePath());
    } catch (IOException ioEx) {
      LOG.error("Unable to initialize Recon OM DB snapshot store.", ioEx);
    }
    if (getStore() != null) {
      initializeOmTables(true);
      omTablesInitialized = true;
    }
  }

  @Override
  public void updateOmDB(File newDbLocation) throws IOException {
    if (getStore() != null) {
      File oldDBLocation = getStore().getDbLocation();
      if (oldDBLocation.exists()) {
        LOG.info("Cleaning up old OM snapshot db at {}.",
            oldDBLocation.getAbsolutePath());
        FileUtils.deleteDirectory(oldDBLocation);
      }
    }
    DBStore current = getStore();
    try {
      initializeNewRdbStore(newDbLocation);
    } finally {
      // Always close DBStore if it's replaced.
      if (current != null && current != getStore()) {
        current.close();
      }
    }
  }

  @Override
  public long getLastSequenceNumberFromDB() {
    RDBStore rocksDBStore = (RDBStore) getStore();
    if (null == rocksDBStore) {
      return 0;
    } else {
      return rocksDBStore.getDb().getLatestSequenceNumber();
    }
  }

  /**
   * Check if OM tables are initialized.
   * @return true if OM Tables are initialized, otherwise false.
   */
  @Override
  public boolean isOmTablesInitialized() {
    return omTablesInitialized;
  }
}