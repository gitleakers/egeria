/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.openmetadata.accessservices.assetcatalog.handlers;

import org.apache.commons.collections4.CollectionUtils;
import org.odpi.openmetadata.accessservices.assetcatalog.builders.AssetConverter;
import org.odpi.openmetadata.accessservices.assetcatalog.model.Type;
import org.odpi.openmetadata.commonservices.repositoryhandler.RepositoryErrorHandler;
import org.odpi.openmetadata.commonservices.repositoryhandler.RepositoryHandler;
import org.odpi.openmetadata.frameworks.connectors.ffdc.InvalidParameterException;
import org.odpi.openmetadata.frameworks.connectors.ffdc.PropertyServerException;
import org.odpi.openmetadata.frameworks.connectors.ffdc.UserNotAuthorizedException;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.OMRSMetadataCollection;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.Classification;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.EntityDetail;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.typedefs.TypeDef;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.typedefs.TypeDefAttribute;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.typedefs.TypeDefLink;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.repositoryconnector.OMRSRepositoryHelper;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.EntityNotKnownException;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.EntityProxyOnlyException;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.RepositoryErrorException;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.TypeErrorException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.odpi.openmetadata.accessservices.assetcatalog.util.Constants.ASSET_ZONE_MEMBERSHIP;
import static org.odpi.openmetadata.accessservices.assetcatalog.util.Constants.DISPLAY_NAME;
import static org.odpi.openmetadata.accessservices.assetcatalog.util.Constants.GUID_PARAMETER;
import static org.odpi.openmetadata.accessservices.assetcatalog.util.Constants.REFERENCEABLE;

/**
 * Common  Handler supports the lookup types and metadata collection.
 * These methods are used in the multiple handlers.
 * It runs on the server-side of the Asset Catalog OMAS, fetches the types, metadata collection using the RepositoryHandler.
 */
public class CommonHandler {

    public static final String ZONE_MEMBERSHIP = "zoneMembership";
    private final String sourceName;
    private final RepositoryHandler repositoryHandler;
    private final OMRSRepositoryHelper repositoryHelper;
    private final RepositoryErrorHandler errorHandler;

    /**
     * Construct the handler information needed to interact with the repository services
     *
     * @param sourceName        the name of the component
     * @param repositoryHandler manages calls to the repository services
     * @param repositoryHelper  provides utilities for manipulating the repository services objects
     * @param errorHandler      provides common validation routines for the other handler classes
     */
    CommonHandler(String sourceName, RepositoryHandler repositoryHandler, OMRSRepositoryHelper repositoryHelper, RepositoryErrorHandler errorHandler) {
        this.sourceName = sourceName;
        this.repositoryHandler = repositoryHandler;
        this.repositoryHelper = repositoryHelper;
        this.errorHandler = errorHandler;
    }

    OMRSMetadataCollection getOMRSMetadataCollection() {
        return repositoryHandler.getMetadataCollection();
    }

    /**
     * Returns a list containing the type and all of the sub-types of the provided type
     *
     * @param userId      user identifier that issues the call
     * @param typeDefName the type definition name
     * @return a list of sub-types recursive
     */
    List<Type> getTypeContext(String userId, String typeDefName) {
        List<Type> response = new ArrayList<>();
        TypeDef typeDefByName = repositoryHelper.getTypeDefByName(userId, typeDefName);
        AssetConverter converter = new AssetConverter(sourceName, repositoryHelper);

        if (typeDefByName != null) {
            if (repositoryHelper.getKnownTypeDefGallery() == null
                    || CollectionUtils.isEmpty(repositoryHelper.getKnownTypeDefGallery().getTypeDefs())) {
                return response;
            }
            List<TypeDef> typeDefs = repositoryHelper.getKnownTypeDefGallery().getTypeDefs();

            Type type = converter.convertType(typeDefByName);
            List<Type> subTypes = getSubTypes(typeDefs, type);
            response.add(type);
            response.addAll(subTypes);

            collectSubTypes(subTypes, typeDefs, response);
            response.sort(Comparator.comparing(Type::getName));
        }

        return response;
    }

    /**
     *
     * @param userId      user identifier that issues the call
     * @param typeDefName the type definition name
     * @return the Type if exists, otherwise null
     */
    Type getTypeByTypeDefName(String userId, String typeDefName) {

        TypeDef typeDefByName = repositoryHelper.getTypeDefByName(userId, typeDefName);
        AssetConverter converter = new AssetConverter(sourceName, repositoryHelper);

        if (typeDefByName != null) {
            return converter.convertType(typeDefByName);
        }
        return null;
    }

    /**
     * Returns the global identifier of the type using the type def name
     *
     * @param userId      user identifier that issues the call
     * @param typeDefName the type definition name
     * @return the global identifier of the type
     */
    public String getTypeDefGUID(String userId, String typeDefName) {
        if (typeDefName == null) {
            return null;
        }

        TypeDef typeDefByName = repositoryHelper.getTypeDefByName(userId, typeDefName);
        return Optional.ofNullable(typeDefByName).map(TypeDefLink::getGUID).orElse(null);
    }

    public boolean hasDisplayName(String userId, String typeDefGUID) throws InvalidParameterException {
        String methodName = "hasDisplayName";
        TypeDef typeDefByName = null;
        try {
            typeDefByName = repositoryHelper.getTypeDef(userId, GUID_PARAMETER, typeDefGUID, methodName);
        } catch (TypeErrorException typeErrorException) {
            errorHandler.handleUnsupportedType(typeErrorException, methodName, GUID_PARAMETER);
        }
        List<TypeDefAttribute> allPropertiesForTypeDef = repositoryHelper.getAllPropertiesForTypeDef(sourceName, typeDefByName, methodName);
        if (allPropertiesForTypeDef == null) {
            return false;
        } else {
            return allPropertiesForTypeDef.stream().anyMatch(property -> property.getAttributeName().equals(DISPLAY_NAME));
        }

    }

    /**
     * Fetch the zone membership property
     *
     * @param classifications asset properties
     * @return the list that contains the zone membership
     */
    List<String> getAssetZoneMembership(List<Classification> classifications) {
        String methodName = "getAssetZoneMembership";
        if (CollectionUtils.isEmpty(classifications)) {
            return Collections.emptyList();
        }

        Optional<Classification> assetZoneMembership = getAssetZoneMembershipClassification(classifications);

        if (assetZoneMembership.isPresent()) {
            List<String> zoneMembership = repositoryHelper.getStringArrayProperty(sourceName,
                    ZONE_MEMBERSHIP, assetZoneMembership.get().getProperties(), methodName);

            if (CollectionUtils.isNotEmpty(zoneMembership)) {
                return zoneMembership;
            }
        }

        return Collections.emptyList();
    }

    /**
     * Return the requested entity, converting any errors from the repository services into the local
     * OMAS exceptions.
     *
     * @param userId calling user
     * @param guid   unique identifier for the entity
     * @return entity detail object
     * @throws InvalidParameterException  one of the parameters is null or invalid.
     * @throws UserNotAuthorizedException user not authorized to issue this request.
     * @throws PropertyServerException    problem retrieving the entity.
     */
    EntityDetail getEntityByGUID(String userId,
                                 String guid,
                                 String type) throws InvalidParameterException, UserNotAuthorizedException, PropertyServerException {

        String entityTypeName = type == null ? "Unknown" : type;
        String methodName = "getEntityByGUID";
        try {
            return repositoryHandler.getMetadataCollection().getEntityDetail(userId, guid);
        } catch (org.odpi.openmetadata.repositoryservices.ffdc.exception.InvalidParameterException | RepositoryErrorException e) {
            if(("" + e.getReportedHTTPCode()).startsWith("4")){
                errorHandler.handleUnknownEntity(e, guid, entityTypeName, methodName, GUID_PARAMETER);
            }
            errorHandler.handleRepositoryError(e, methodName);
        } catch (EntityNotKnownException e) {
            errorHandler.handleUnknownEntity(e, guid, entityTypeName, methodName, GUID_PARAMETER);
        } catch (EntityProxyOnlyException e) {
            errorHandler.handleEntityProxy(e, guid, entityTypeName, methodName, GUID_PARAMETER);
        } catch (org.odpi.openmetadata.repositoryservices.ffdc.exception.UserNotAuthorizedException e) {
            errorHandler.handleUnauthorizedUser(userId, methodName);
        }

        return null;
    }

    /**
     * Return a list of the types def GUIDs
     *
     * @param userId calling user
     * @param types  list of the type def names
     * @return a list of type def GUIDs
     */
    List<String> getTypesGUID(String userId, List<String> types) {
        if (CollectionUtils.isEmpty(types)) {
            return Collections.emptyList();
        }
        return types.stream().map(type -> repositoryHelper.getTypeDefByName(userId, type).getGUID()).collect(Collectors.toList());
    }

    private void collectSubTypes(List<Type> types, List<TypeDef> activeTypeDefs, List<Type> collector) {
        for (Type currentSubType : types) {
            List<Type> subTypes = getSubTypes(activeTypeDefs, currentSubType);
            collector.addAll(subTypes);
            collectSubTypes(subTypes, activeTypeDefs, collector);
        }
    }

    private List<Type> getSubTypes(List<TypeDef> activeTypeDefs, Type type) {
        String typeName = type.getName();
        AssetConverter converter = new AssetConverter(sourceName, repositoryHelper);

        List<Type> subTypes = new ArrayList<>();
        for (TypeDef typeDef : activeTypeDefs) {
            if (typeDef.getSuperType() != null && typeDef.getSuperType().getName().equals(typeName)) {

                subTypes.add(converter.convertType(typeDef));
            }
        }
        return subTypes;
    }

    private Optional<Classification> getAssetZoneMembershipClassification(List<Classification> classifications) {
        return classifications.stream()
                .filter(classification -> classification.getName().equals(ASSET_ZONE_MEMBERSHIP))
                .findFirst();
    }

    Set<String> collectSuperTypes(String userId, String typeDefName) {
        Set<String> superTypes = new HashSet<>();

        TypeDef typeDefByName = repositoryHelper.getTypeDefByName(userId, typeDefName);
        if (typeDefByName != null) {
            collectSuperTypes(userId, typeDefByName, superTypes);
        }

        return superTypes;
    }

    private void collectSuperTypes(String userId, TypeDef type, Set<String> superTypes) {
        if (type.getName().equals(REFERENCEABLE)) {
            return;
        }
        superTypes.add(type.getName());
        TypeDef typeDefByName = repositoryHelper.getTypeDefByName(userId, type.getSuperType().getName());
        if (typeDefByName != null) {
            collectSuperTypes(userId, typeDefByName, superTypes);
        }
    }
}
