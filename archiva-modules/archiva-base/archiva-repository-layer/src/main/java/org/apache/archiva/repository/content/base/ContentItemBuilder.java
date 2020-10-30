package org.apache.archiva.repository.content.base;

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

import org.apache.archiva.repository.ManagedRepositoryContent;
import org.apache.archiva.repository.content.base.builder.OptBuilder;
import org.apache.archiva.repository.content.base.builder.WithAssetBuilder;
import org.apache.archiva.repository.content.base.builder.WithRepositoryBuilder;
import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.commons.lang3.StringUtils;

/**
 * Builder for content item. Must be extended by subclasses.
 * The builder uses chained interfaces for building the required attributes. That means you have to set
 * some certain attributes, before you can build the content item instance via the {@link #build()} method.
 * <p>
 * Subclasses should extend from this class and provide the interface/class for the destination item,
 * a interface for the optional attributes and a interface that is returned after the last required attribute is
 * set.
 * <p>
 * The interface for optional attributes should inherit from {@link OptBuilder}
 *
 * @param <I> the item class that should be built
 * @param <O> the class/interface for the optional attributes
 * @param <N> the class/interface for the next (required) attribute after the base attributes are set
 */
abstract class ContentItemBuilder<I extends BaseContentItem, O extends OptBuilder<I, O>, N>
    implements WithRepositoryBuilder, WithAssetBuilder<N>,
    OptBuilder<I, O>
{

    protected I item;

    protected ContentItemBuilder( I item )
    {
        this.item = item;
    }

    protected abstract O getOptBuilder( );

    protected abstract N getNextBuilder( );

    @Override
    public WithAssetBuilder<N> withRepository( ManagedRepositoryContent repository )
    {
        if ( repository == null )
        {
            throw new IllegalArgumentException( "Repository may not be null" );
        }
        ( (BaseContentItem) item ).repository = repository;
        return this;
    }

    @Override
    public N withAsset( StorageAsset asset )
    {
        if ( asset == null )
        {
            throw new IllegalArgumentException( "Asset may not be null" );
        }
        ( (BaseContentItem) item ).asset = asset;
        return getNextBuilder( );
    }

    @Override
    public O withAttribute( String key, String value )
    {
        if ( StringUtils.isEmpty( key ) )
        {
            throw new IllegalArgumentException( "Attribute key may not be null" );
        }
        item.putAttribute( key, value );
        return getOptBuilder( );
    }

    protected void setRepository( ManagedRepositoryContent repository )
    {
        item.repository = repository;
    }

    @Override
    public I build( )
    {
        return item;
    }

}
