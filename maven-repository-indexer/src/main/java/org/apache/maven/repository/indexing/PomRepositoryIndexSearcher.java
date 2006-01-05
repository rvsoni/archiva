package org.apache.maven.repository.indexing;

/**
 * Copyright 2005-2006 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.document.Document;
import org.apache.maven.artifact.factory.ArtifactFactory;

/**
 * @author Edwin Punzalan
 */
public class PomRepositoryIndexSearcher
    extends AbstractRepositoryIndexSearcher
{
    private static final String GROUPID = "groupId";

    private static final String ARTIFACTID = "artifactId";

    private static final String VERSION = "version";

    private static final String PACKAGING = "packaging";

    private ArtifactFactory factory;

    public PomRepositoryIndexSearcher( RepositoryIndex index, ArtifactFactory artifactFactory )
    {
        super( index );
        this.factory = artifactFactory;
    }

    protected Object createSearchedObjectFromIndexDocument( Document doc )
    {
        String groupId = doc.get( PomRepositoryIndex.FLD_GROUPID ) ;
        String artifactId = doc.get( PomRepositoryIndex.FLD_ARTIFACTID );
        String version = doc.get( PomRepositoryIndex.FLD_VERSION );
        String packaging = doc.get( PomRepositoryIndex.FLD_PACKAGING );
        return factory.createBuildArtifact( groupId, artifactId, version, packaging );
    }
}
