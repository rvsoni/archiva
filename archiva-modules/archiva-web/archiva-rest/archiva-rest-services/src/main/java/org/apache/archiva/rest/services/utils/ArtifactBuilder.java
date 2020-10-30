package org.apache.archiva.rest.services.utils;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.maven2.model.Artifact;
import org.apache.archiva.metadata.model.ArtifactMetadata;
import org.apache.archiva.metadata.maven.model.MavenArtifactFacet;
import org.apache.archiva.repository.content.BaseRepositoryContentLayout;
import org.apache.archiva.repository.ManagedRepositoryContent;
import org.apache.archiva.repository.content.LayoutException;
import org.apache.archiva.repository.content.base.ArchivaItemSelector;
import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.archiva.repository.storage.util.StorageUtil;
import org.apache.commons.lang3.StringUtils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Olivier Lamy
 * @since 1.4-M3
 */
public class ArtifactBuilder
{

    private ManagedRepositoryContent managedRepositoryContent;

    private ArtifactMetadata artifactMetadata;

    public ArtifactBuilder()
    {
        // no op
    }


    public ArtifactBuilder withManagedRepositoryContent( ManagedRepositoryContent managedRepositoryContent )
    {
        this.managedRepositoryContent = managedRepositoryContent;
        return this;
    }

    public ArtifactBuilder forArtifactMetadata( ArtifactMetadata artifactMetadata )
    {
        this.artifactMetadata = artifactMetadata;
        return this;
    }

    public Artifact build()
    {
        String type = null, classifier = null;

        MavenArtifactFacet facet = (MavenArtifactFacet) artifactMetadata.getFacet( MavenArtifactFacet.FACET_ID );
        if ( facet != null )
        {
            type = facet.getType();
            classifier = facet.getClassifier();
        }

        ArchivaItemSelector.Builder selectorBuilder = ArchivaItemSelector.builder( )
            .withNamespace( artifactMetadata.getNamespace( ) )
            .withProjectId( artifactMetadata.getProject( ) )
            .withVersion( artifactMetadata.getProjectVersion( ) )
            .withArtifactId( artifactMetadata.getProject( ) )
            .withArtifactVersion( artifactMetadata.getVersion( ) );
        if (StringUtils.isNotEmpty( type ) ) {
            selectorBuilder.withType( type );
        }
        if (StringUtils.isNotEmpty( classifier )) {
            selectorBuilder.withClassifier( classifier );
        }


        BaseRepositoryContentLayout layout;
        try
        {
             layout = managedRepositoryContent.getLayout( BaseRepositoryContentLayout.class );
        }
        catch ( LayoutException e )
        {
            throw new RuntimeException( "Could not convert to layout " + e.getMessage( ) );
        }
        org.apache.archiva.repository.content.Artifact repoArtifact = layout.getArtifact( selectorBuilder.build( ) );

        String extension = repoArtifact.getExtension();

        Artifact artifact = new Artifact( repoArtifact.getVersion( ).getProject( ).getNamespace( ).getId( ), repoArtifact.getId( ), repoArtifact.getArtifactVersion( ) );
        artifact.setRepositoryId( artifactMetadata.getRepositoryId() );
        artifact.setClassifier( classifier );
        artifact.setPackaging( type );
        artifact.setType( type );
        artifact.setFileExtension( extension );
        artifact.setPath( managedRepositoryContent.toPath( repoArtifact ) );
        // TODO: find a reusable formatter for this
        double s = this.artifactMetadata.getSize();
        String symbol = "b";
        if ( s > 1024 )
        {
            symbol = "K";
            s /= 1024;

            if ( s > 1024 )
            {
                symbol = "M";
                s /= 1024;

                if ( s > 1024 )
                {
                    symbol = "G";
                    s /= 1024;
                }
            }
        }
        artifact.setContext( managedRepositoryContent.getId() );
        DecimalFormat df = new DecimalFormat( "#,###.##", new DecimalFormatSymbols( Locale.US ) );
        artifact.setSize( df.format( s ) + " " + symbol );

        artifact.setId( repoArtifact.getId() + "-" + repoArtifact.getArtifactVersion() + "." + repoArtifact.getType() );

        return artifact;

    }


    /**
     * Extract file extension
     */
    String getExtensionFromFile( StorageAsset file )
    {
        // we are just interested in the section after the last -
        String[] parts = file.getName().split( "-" );
        if ( parts.length > 0 )
        {
            // get anything after a dot followed by a letter a-z, including other dots
            Pattern p = Pattern.compile( "\\.([a-z]+[a-z0-9\\.]*)" );
            Matcher m = p.matcher( parts[parts.length - 1] );
            if ( m.find() )
            {
                return m.group( 1 );
            }
        }
        // just in case
        return StorageUtil.getExtension( file );
    }

}
