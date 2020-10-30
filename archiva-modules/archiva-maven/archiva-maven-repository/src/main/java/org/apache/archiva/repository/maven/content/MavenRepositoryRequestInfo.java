package org.apache.archiva.repository.maven.content;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.RepositoryRequestInfo;
import org.apache.archiva.repository.UnsupportedFeatureException;
import org.apache.archiva.repository.content.BaseRepositoryContentLayout;
import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.LayoutException;
import org.apache.archiva.repository.features.RepositoryFeature;
import org.apache.archiva.repository.metadata.base.MetadataTools;
import org.apache.commons.lang3.StringUtils;

/**
 * RepositoryRequest is used to determine the type of request that is incoming, and convert it to an appropriate
 * ArtifactReference.
 */
public class MavenRepositoryRequestInfo implements RepositoryRequestInfo
{
    ManagedRepository repository;

    public MavenRepositoryRequestInfo(ManagedRepository repository)
    {
        this.repository = repository;
    }

    @Override
    public ItemSelector toItemSelector( String requestPath ) throws LayoutException
    {
        return repository.getContent( ).toItemSelector( requestPath );
    }

    /**
     * <p>
     * Tests the path to see if it conforms to the expectations of a metadata request.
     * </p>
     * <p>
     * NOTE: This does a cursory check on the path's last element.  A result of true
     * from this method is not a guarantee that the metadata is in a valid format, or
     * that it even contains data.
     * </p>
     *
     * @param requestedPath the path to test.
     * @return true if the requestedPath is likely a metadata request.
     */
    public boolean isMetadata( String requestedPath )
    {
        return requestedPath.endsWith( "/" + MetadataTools.MAVEN_METADATA );
    }

    /**
     * @param requestedPath
     * @return true if the requestedPath is likely an archetype catalog request.
     */
    public boolean isArchetypeCatalog( String requestedPath )
    {
        return requestedPath.endsWith( "/" + MetadataTools.MAVEN_ARCHETYPE_CATALOG );
    }

    /**
     * <p>
     * Tests the path to see if it conforms to the expectations of a support file request.
     * </p>
     * <p>
     * Tests for <code>.sha1</code>, <code>.md5</code>, <code>.asc</code>, and <code>.php</code>.
     * </p>
     * <p>
     * NOTE: This does a cursory check on the path's extension only.  A result of true
     * from this method is not a guarantee that the support resource is in a valid format, or
     * that it even contains data.
     * </p>
     *
     * @param requestedPath the path to test.
     * @return true if the requestedPath is likely that of a support file request.
     */
    public boolean isSupportFile( String requestedPath )
    {
        int idx = requestedPath.lastIndexOf( '.' );
        if ( idx <= 0 )
        {
            return false;
        }

        String ext = requestedPath.substring( idx );
        return ( ".sha1".equals( ext ) || ".md5".equals( ext ) || ".asc".equals( ext ) || ".pgp".equals( ext ) );
    }

    public boolean isMetadataSupportFile( String requestedPath )
    {
        if ( isSupportFile( requestedPath ) )
        {
            String basefilePath = StringUtils.substring( requestedPath, 0, requestedPath.lastIndexOf( '.' ) );
            if ( isMetadata( basefilePath ) )
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public String getLayout(String requestPath) {
        if (isDefault(requestPath)) {
            return "default";
        } else if (isLegacy(requestPath)) {
            return "legacy";
        } else {
            return "unknown";
        }
    }

    /**
     * <p>
     * Tests the path to see if it conforms to the expectations of a default layout request.
     * </p>
     * <p>
     * NOTE: This does a cursory check on the count of path elements only.  A result of
     * true from this method is not a guarantee that the path sections are valid and
     * can be resolved to an artifact reference.  use {@link #toItemSelector(String)}
     * if you want a more complete analysis of the validity of the path.
     * </p>
     *
     * @param requestedPath the path to test.
     * @return true if the requestedPath is likely that of a default layout request.
     */
    private boolean isDefault( String requestedPath )
    {
        if ( StringUtils.isBlank( requestedPath ) )
        {
            return false;
        }

        String pathParts[] = StringUtils.splitPreserveAllTokens( requestedPath, '/' );
        if ( pathParts.length > 3 )
        {
            return true;
        }
        else if ( pathParts.length == 3 )
        {
            // check if artifact-level metadata (ex. eclipse/jdtcore/maven-metadata.xml)
            if ( isMetadata( requestedPath ) )
            {
                return true;
            }
            else
            {
                // check if checksum of artifact-level metadata (ex. eclipse/jdtcore/maven-metadata.xml.sha1)
                int idx = requestedPath.lastIndexOf( '.' );
                if ( idx > 0 )
                {
                    String base = requestedPath.substring( 0, idx );
                    if ( isMetadata( base ) && isSupportFile( requestedPath ) )
                    {
                        return true;
                    }
                }

                return false;
            }
        }
        else
        {
            return false;
        }
    }

    /**
     * <p>
     * Tests the path to see if it conforms to the expectations of a legacy layout request.
     * </p>
     * <p>
     * NOTE: This does a cursory check on the count of path elements only.  A result of
     * true from this method is not a guarantee that the path sections are valid and
     * can be resolved to an artifact reference.  Use {@link #toItemSelector(String)}
     * if you want a more complete analysis of the validity of the path.
     * </p>
     *
     * @param requestedPath the path to test.
     * @return true if the requestedPath is likely that of a legacy layout request.
     */
    private boolean isLegacy( String requestedPath )
    {
        if ( StringUtils.isBlank( requestedPath ) )
        {
            return false;
        }

        String pathParts[] = StringUtils.splitPreserveAllTokens( requestedPath, '/' );
        return pathParts.length == 3;
    }

    /**
     * Adjust the requestedPath to conform to the native layout of the provided {@link BaseRepositoryContentLayout}.
     *
     * @param requestedPath the incoming requested path.
     * @return the adjusted (to native) path.
     * @throws LayoutException if the path cannot be parsed.
     */
    public String toNativePath( String requestedPath)
        throws LayoutException
    {
        if ( StringUtils.isBlank( requestedPath ) )
        {
            throw new LayoutException( "Request Path is blank." );
        }

        String referencedResource = requestedPath;
        // No checksum by default.
        String supportfile = "";

        // Figure out support file, and actual referencedResource.
        if ( isSupportFile( requestedPath ) )
        {
            int idx = requestedPath.lastIndexOf( '.' );
            referencedResource = requestedPath.substring( 0, idx );
            supportfile = requestedPath.substring( idx );
        }

        if ( isMetadata( referencedResource ) )
        {
            /* Nothing to translate.
             * Default layout is the only layout that can contain maven-metadata.xml files, and
             * if the managedRepository is layout legacy, this request would never occur.
             */
            if (requestedPath.startsWith( "/" )) {
                return requestedPath;
            } else
            {
                return "/"+requestedPath;
            }
        }



        // Treat as an artifact reference.
        String adjustedPath = repository.getContent( ).toPath( repository.getContent( ).toItem( requestedPath ) );
        return adjustedPath + supportfile;
    }

    @Override
    public <T extends RepositoryFeature<T>> RepositoryFeature<T> getFeature(Class<T> clazz) throws UnsupportedFeatureException {
        return null;
    }

    @Override
    public <T extends RepositoryFeature<T>> boolean supportsFeature(Class<T> clazz) {
        return false;
    }
}
