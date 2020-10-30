package org.apache.archiva.consumers.core;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.configuration.FileTypes;
import org.apache.archiva.consumers.AbstractMonitoredConsumer;
import org.apache.archiva.consumers.ConsumerException;
import org.apache.archiva.consumers.KnownRepositoryContentConsumer;
import org.apache.archiva.repository.content.BaseRepositoryContentLayout;
import org.apache.archiva.repository.content.LayoutException;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.ManagedRepositoryContent;
import org.apache.archiva.repository.RepositoryException;
import org.apache.archiva.repository.RepositoryNotFoundException;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.repository.content.Artifact;
import org.apache.archiva.repository.content.Project;
import org.apache.archiva.repository.metadata.RepositoryMetadataException;
import org.apache.archiva.repository.metadata.base.MetadataTools;
import org.apache.archiva.repository.storage.StorageAsset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MetadataUpdaterConsumer will create and update the metadata present within the repository.
 */
@Service( "knownRepositoryContentConsumer#metadata-updater" )
@Scope( "prototype" )
public class MetadataUpdaterConsumer
    extends AbstractMonitoredConsumer
    implements KnownRepositoryContentConsumer
    // it's prototype bean so we assume configuration won't change during a run
    //, RegistryListener
{
    private Logger log = LoggerFactory.getLogger( MetadataUpdaterConsumer.class );

    /**
     * default-value="metadata-updater"
     */
    private String id = "metadata-updater";

    private String description = "Update / Create maven-metadata.xml files";

    @Inject
    private RepositoryRegistry repositoryRegistry;

    @Inject
    private MetadataTools metadataTools;

    @Inject
    private FileTypes filetypes;

    private static final String TYPE_METADATA_BAD_INTERNAL_REF = "metadata-bad-internal-ref";

    private static final String TYPE_METADATA_WRITE_FAILURE = "metadata-write-failure";

    private static final String TYPE_METADATA_IO = "metadata-io-warning";

    private ManagedRepositoryContent repository;

    private StorageAsset repositoryDir;

    private List<String> includes = new ArrayList<>( 0 );

    private long scanStartTimestamp = 0;

    @Override
    public String getDescription( )
    {
        return description;
    }

    @Override
    public String getId( )
    {
        return id;
    }

    public void setIncludes( List<String> includes )
    {
        this.includes = includes;
    }

    @Override
    public void beginScan( ManagedRepository repoConfig, Date whenGathered )
        throws ConsumerException
    {
        try
        {
            ManagedRepository repo = repositoryRegistry.getManagedRepository( repoConfig.getId( ) );
            if (repo==null) {
                throw new RepositoryNotFoundException( "Repository not found: "+repoConfig.getId() );
            }
            this.repository = repo.getContent();
            if (this.repository==null) {
                throw new RepositoryNotFoundException( "Repository content not found: "+repoConfig.getId() );
            }
            this.repositoryDir = repository.getRepository().getRoot();
            this.scanStartTimestamp = System.currentTimeMillis( );
        }
        catch ( RepositoryException e )
        {
            throw new ConsumerException( e.getMessage( ), e );
        }
    }

    @Override
    public void beginScan( ManagedRepository repository, Date whenGathered, boolean executeOnEntireRepo )
        throws ConsumerException
    {
        beginScan( repository, whenGathered );
    }

    @Override
    public void completeScan( )
    {
        /* do nothing here */
    }

    @Override
    public void completeScan( boolean executeOnEntireRepo )
    {
        completeScan( );
    }

    @Override
    public List<String> getExcludes( )
    {
        return getDefaultArtifactExclusions( );
    }

    @Override
    public List<String> getIncludes( )
    {
        return this.includes;
    }

    @Override
    public void processFile( String path )
        throws ConsumerException
    {
        // Ignore paths like .index etc
        if ( !path.startsWith( "." ) )
        {
            try
            {
                BaseRepositoryContentLayout layout = repository.getLayout( BaseRepositoryContentLayout.class );
                Artifact artifact = layout.getArtifact( path );
                updateVersionMetadata( artifact, path );
                updateProjectMetadata( artifact, path );
            }
            catch ( LayoutException e )
            {
                log.info( "Not processing path that is not an artifact: {} ({})", path, e.getMessage( ) );
            }
        }
    }

    @Override
    public void processFile( String path, boolean executeOnEntireRepo )
        throws Exception
    {
        processFile( path );
    }

    private void updateProjectMetadata( Artifact artifact, String path )
    {
        try
        {
            Project proj = artifact.getProject( );

            String metadataPath = repository.toPath( proj );

            StorageAsset projectMetadata = this.repositoryDir.resolve( metadataPath );

            if ( projectMetadata.exists() && ( projectMetadata.getModificationTime().toEpochMilli() >= this.scanStartTimestamp ) )
            {
                // This metadata is up to date. skip it.
                log.debug( "Skipping uptodate metadata: {}", metadataPath );
                return;
            }
            metadataTools.updateMetadata( this.repository, metadataPath );
            log.debug( "Updated metadata: {}", metadataPath );
        }
        catch ( RepositoryMetadataException e )
        {
            log.error( "Unable to write project metadat for artifact [{}]:", path, e );
            triggerConsumerError( TYPE_METADATA_WRITE_FAILURE,
                "Unable to write project metadata for artifact [" + path + "]: " + e.getMessage( ) );
        }
    }

    private void updateVersionMetadata( Artifact artifact, String path )
    {

        try
        {
            String metadataPath = this.metadataTools.toPath( artifact.getVersion() );

            StorageAsset projectMetadata = this.repositoryDir.resolve( metadataPath );

            if ( projectMetadata.exists() && ( projectMetadata.getModificationTime().toEpochMilli() >= this.scanStartTimestamp ) )
            {
                // This metadata is up to date. skip it.
                log.debug( "Skipping uptodate metadata: {}", metadataPath );
                return;
            }

            metadataTools.updateMetadata( this.repository, metadataPath );
            log.debug( "Updated metadata: {}", metadataPath );
        }
        catch ( RepositoryMetadataException e )
        {
            log.error( "Unable to write version metadata for artifact [{}]: ", path, e );
            triggerConsumerError( TYPE_METADATA_WRITE_FAILURE,
                "Unable to write version metadata for artifact [" + path + "]: " + e.getMessage( ) );
        }
    }

    private void initIncludes( )
    {
        includes = new ArrayList<>( filetypes.getFileTypePatterns( FileTypes.ARTIFACTS ) );
    }

    @PostConstruct
    public void initialize( )
    {
        //configuration.addChangeListener( this );

        initIncludes( );
    }
}
