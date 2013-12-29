package com.breezejs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.*;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.id.Assigned;
import org.hibernate.id.IdentifierGenerator;
import org.hibernate.id.IdentityGenerator;
import org.hibernate.mapping.*;
import org.hibernate.metadata.*;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.*;

/**
 * Builds a data structure containing the metadata required by Breeze.
 * @see http://www.breezejs.com/documentation/breeze-metadata-format
 * @author Steve
 *
 */
public class BreezeHibernateMetadata {
	
    private SessionFactory _sessionFactory;
    private Configuration _configuration;
    private HashMap<String, Object> _map;
    private List<HashMap<String, Object>> _typeList;
    private HashMap<String, Object> _resourceMap;
    private HashSet<String> _typeNames;
    private HashMap<String, String> _fkMap;

    public static final String FK_MAP = "fkMap";
    
    public BreezeHibernateMetadata(SessionFactory sessionFactory, Configuration configuration)
    {
        _sessionFactory = sessionFactory;
        _configuration = configuration;
    }
    
    /**
     *  Build the Breeze metadata as a nested HashMap.  
     *  The result can be converted to JSON and sent to the Breeze client.
     */
    public Map<String, Object> buildMetadata()
    {
        initMap();

        Map<String, ClassMetadata> classMeta = _sessionFactory.getAllClassMetadata();
        //Map<String, ICollectionMetadata> collectionMeta = _sessionFactory.GetAllCollectionMetadata();

		for (ClassMetadata meta : classMeta.values())
        {
            addClass(meta);
        }
		
        return _map;
    }
    

    /**
     *  Populate the metadata header.
     */
    void initMap()
    {
        _map = new LinkedHashMap<String, Object>();
        _typeList = new ArrayList<HashMap<String, Object>>();
        _typeNames = new HashSet<String>();
        _resourceMap = new HashMap<String, Object>();
        _fkMap = new HashMap<String, String>();
        _map.put("localQueryComparisonOptions", "caseInsensitiveSQL");
        _map.put("structuralTypes", _typeList);
        _map.put("resourceEntityTypeMap",_resourceMap);
        _map.put(FK_MAP, _fkMap);
    }

    /**
     * Add the metadata for an entity.
     * @param meta
     */
    void addClass(ClassMetadata meta)
    {
        Class type = meta.getMappedClass();

        String classKey = getEntityTypeName(type);
        HashMap<String, Object> cmap = new LinkedHashMap<String, Object>();
        _typeList.add(cmap);

        cmap.put("shortName", type.getSimpleName());
        cmap.put("namespace", type.getPackage().getName());

        PersistentClass persistentClass = _configuration.getClassMapping(classKey);
        PersistentClass superClass = persistentClass.getSuperclass();
        if (superClass != null) 
        {
            Class superType = superClass.getMappedClass();
            String baseTypeName = getEntityTypeName(superType); 
            cmap.put("baseTypeName", baseTypeName);
        }

        if (meta instanceof EntityPersister) {
            EntityPersister entityPersister = (EntityPersister) meta;
            IdentifierGenerator generator = entityPersister != null ? entityPersister.getIdentifierGenerator() : null;
            if (generator != null)
            {
                String genType = null;
                if (generator instanceof IdentityGenerator) genType = "Identity";
                else if (generator instanceof Assigned) genType = "None";
                else genType = "KeyGenerator";
                cmap.put("autoGeneratedKeyType", genType); // TODO find the real generator
            }
        }
        

        String resourceName = pluralize(type.getSimpleName()); // TODO find the real name
        cmap.put("defaultResourceName", resourceName);
        _resourceMap.put(resourceName, classKey);

        ArrayList<HashMap<String, Object>> dataArrayList = new ArrayList<HashMap<String, Object>>();
        cmap.put("dataProperties", dataArrayList);
        ArrayList<HashMap<String, Object>> navArrayList = new ArrayList<HashMap<String, Object>>();
        cmap.put("navigationProperties", navArrayList);

        addClassProperties(meta, persistentClass, dataArrayList, navArrayList);
    }

    /**
     * Add the properties for an entity.
     * @param meta
     * @param pClass
     * @param dataArrayList - will be populated with the data properties of the entity
     * @param navArrayList - will be populated with the navigation properties of the entity
     */
    void addClassProperties(ClassMetadata meta, PersistentClass pClass, ArrayList<HashMap<String, Object>> dataArrayList, ArrayList<HashMap<String, Object>> navArrayList)
    {
        // maps column names to their related data properties.  Used in MakeAssociationProperty to convert FK column names to entity property names.
        HashMap<String, HashMap<String, Object>> relatedDataPropertyMap = new HashMap<String, HashMap<String, Object>>();

        AbstractEntityPersister persister = (AbstractEntityPersister) meta;
        Class type = pClass.getMappedClass();

        String[] propNames = meta.getPropertyNames();
        Type[] propTypes = meta.getPropertyTypes();
        boolean[] propNull = meta.getPropertyNullability();
        for (int i = 0; i < propNames.length; i++)
        {
            String propName = propNames[i];
            Property pClassProp = pClass.getProperty(propName);
            if (!hasOwnProperty(pClass, pClassProp)) continue;  // skip property defined on superclass

            Type propType = propTypes[i];
            if (!propType.isAssociationType())    // skip association types until we handle all the data types, so the relatedDataPropertyMap will be populated.
            {
                ArrayList<Selectable> propColumns = getColumns(pClassProp);
                if (propType.isComponentType())
                {
                    // complex type
                    ComponentType compType = (ComponentType)propType;
                    String complexTypeName = addComponent(compType, propColumns);
                    HashMap<String, Object> compMap = new HashMap<String, Object>();
                    compMap.put("nameOnServer", propName);
                    compMap.put("complexTypeName", complexTypeName);
                    compMap.put("isNullable", propNull[i]);
                    dataArrayList.add(compMap);
                }
                else
                {
                    // data property
                    Column col = propColumns.size() == 1 ? (Column) propColumns.get(0) : null;
                    boolean isKey = meta.hasNaturalIdentifier() && contains(meta.getNaturalIdentifierProperties(), i);
                    boolean isVersion = meta.isVersioned() && i == meta.getVersionProperty();

                    HashMap<String, Object> dmap = makeDataProperty(propName, propType.getName(), propNull[i], col, isKey, isVersion);
                    dataArrayList.add(dmap);

                    String columnNameString = getPropertyColumnNames(persister, propName); 
                    relatedDataPropertyMap.put(columnNameString, dmap);
                }
            }
        }


        // Hibernate identifiers are excluded from the list of data properties, so we have to add them separately
        if (meta.hasIdentifierProperty() && hasOwnProperty(pClass, meta.getIdentifierPropertyName()))
        {
            HashMap<String, Object> dmap = makeDataProperty(meta.getIdentifierPropertyName(), meta.getIdentifierType().getName(), false, null, true, false);
            dataArrayList.add(0, dmap);

            String columnNameString = getPropertyColumnNames(persister, meta.getIdentifierPropertyName());
            relatedDataPropertyMap.put(columnNameString, dmap);
        }
        else if (meta.getIdentifierType() != null && meta.getIdentifierType().isComponentType() 
            && pClass.getIdentifier() instanceof Component && ((Component)pClass.getIdentifier()).getOwner() == pClass)
        {
            // composite key is a ComponentType
            ComponentType compType = (ComponentType)meta.getIdentifierType();
            String[] compNames = compType.getPropertyNames();
            for (int i = 0; i < compNames.length; i++)
            {
                String compName = compNames[i];

                Type propType = compType.getSubtypes()[i];
                if (!propType.isAssociationType())
                {
                    HashMap<String, Object> dmap = makeDataProperty(compName, propType.getName(), compType.getPropertyNullability()[i], null, true, false);
                    dataArrayList.add(0, dmap);
                }
                else
                {
                    ManyToOneType manyToOne = (ManyToOneType) propType;
                    //var joinable = manyToOne.getAssociatedJoinable(this._sessionFactory);
                    String propColumnNames = getPropertyColumnNames(persister, compName);

                    HashMap<String, Object> assProp = makeAssociationProperty(type, (AssociationType)propType, compName, propColumnNames, pClass, relatedDataPropertyMap, true);
                    navArrayList.add(assProp);
                }
            }
        }

        // We do the association properties after the data properties, so we can do the foreign key lookups
        for (int i = 0; i < propNames.length; i++)
        {
            String propName = propNames[i];
            if (!hasOwnProperty(pClass, propName)) continue;  // skip property defined on superclass 

            Type propType = propTypes[i];
            if (propType.isAssociationType())
            {
                // navigation property
                String propColumnNames = getPropertyColumnNames(persister, propName);
                HashMap<String, Object> assProp = makeAssociationProperty(type, (AssociationType)propType, propName, propColumnNames, pClass, relatedDataPropertyMap, false);
                navArrayList.add(assProp);
            }
        }
    }

    boolean hasOwnProperty(PersistentClass pClass, String propName) 
    {
        return pClass.getProperty(propName).getPersistentClass() == pClass;
    }

    boolean hasOwnProperty(PersistentClass pClass, Property prop)
    {
        return prop.getPersistentClass() == pClass;
    }
    
    ArrayList<Selectable> getColumns(Property pClassProp)
    {
    	Iterator iter = pClassProp.getColumnIterator();
    	ArrayList<Selectable> list = new ArrayList<Selectable>();
    	while (iter.hasNext())
    	    list.add((Selectable) iter.next());
    	return list;
    }
    
    boolean contains(int[] array, int x)
    {
    	for (int j = 0; j < array.length; j++)
    	{
    		if (array[j] == x) return true;
    	}
    	return false;
    }

    /**
     * Adds a complex type definition
     * @param compType - The complex type
     * @param propColumns - The columns which the complex type spans.  These are used to get the length and defaultValues
     * @return The class name and namespace
     */
    String addComponent(ComponentType compType, List<Selectable> propColumns)
    {
        Class type = compType.getReturnedClass();

        // "Location:#Breeze.Nhibernate.NorthwindIBModel"
        String classKey = getEntityTypeName(type);
        if (_typeNames.contains(classKey))
        {
            // Only add a complex type definition once.
            return classKey;
        }

        HashMap<String, Object> cmap = new HashMap<String, Object>();
        _typeList.add(0, cmap);
        _typeNames.add(classKey);

        cmap.put("shortName", type.getSimpleName());
        cmap.put("namespace", type.getPackage().getName());
        cmap.put("isComplexType", true);

        ArrayList<HashMap<String, Object>> dataArrayList = new ArrayList<HashMap<String, Object>>();
        cmap.put("dataProperties", dataArrayList);

        String[] propNames = compType.getPropertyNames();
        Type[] propTypes = compType.getSubtypes();
        boolean[] propNull = compType.getPropertyNullability();

        int colIndex = 0;
        for (int i = 0; i < propNames.length; i++)
        {
            Type propType = propTypes[i];
            String propName = propNames[i];
            if (propType.isComponentType())
            {
                // nested complex type
                ComponentType compType2 = (ComponentType)propType;
                int span = compType2.getColumnSpan((Mapping) _sessionFactory);
                List<Selectable> subColumns = propColumns.subList(colIndex, colIndex + span);
                String complexTypeName = addComponent(compType2, subColumns);
                HashMap<String, Object> compMap = new HashMap<String, Object>();
                compMap.put("nameOnServer", propName);
                compMap.put("complexTypeName", complexTypeName);
                compMap.put("isNullable", propNull[i]);
                dataArrayList.add(compMap);
                colIndex += span;
            }
            else
            {
                // data property
                Column col = (Column) propColumns.get(colIndex);
                HashMap<String, Object> dmap = makeDataProperty(propName, propType.getName(), propNull[i], col, false, false);
                dataArrayList.add(dmap);
                colIndex++;
            }
        }
        return classKey;
    }

    /**
     * Make data property metadata for the entity
     * @param propName - name of the property on the server
     * @param typeName - data type of the property, e.g. Int32
     * @param isNullable - whether the property is nullable in the database
     * @param col - Column Object, used for maxLength and defaultValue
     * @param isKey - true if this property is part of the key for the entity
     * @param isVersion - true if this property contains the version of the entity (for a concurrency strategy)
     * @return data property definition
     */
    private HashMap<String, Object> makeDataProperty(String propName, String typeName, boolean isNullable, Column col, boolean isKey, boolean isVersion)
    {
        String newType = BreezeTypeMap.get(typeName);
        typeName = newType != null ? newType : typeName;

        HashMap<String, Object> dmap = new LinkedHashMap<String, Object>();
        dmap.put("nameOnServer", propName);
        dmap.put("dataType", typeName);
        dmap.put("isNullable", isNullable);

        if (col != null && col.getDefaultValue() != null)
        {
            dmap.put("defaultValue", col.getDefaultValue());
        }
        if (isKey)
        {
            dmap.put("isPartOfKey", true);
        }
        if (isVersion)
        {
            dmap.put("concurrencyMode", "Fixed");
        }

        ArrayList<HashMap<String, String>> validators = new ArrayList<HashMap<String, String>>();

        if (!isNullable)
        {
            validators.add(newMap("name", "required"));
        }
        if (col != null && col.getLength() > 0)
        {
            dmap.put("maxLength", col.getLength());

            validators.add(newMap("maxLength", Integer.toString(col.getLength()), "name", "maxLength"));
        }

        String validationType = ValidationTypeMap.get(typeName);
        if (validationType != null)
        {
            validators.add(newMap("name", validationType));
        }

        if (!validators.isEmpty())
            dmap.put("validators", validators);

        return dmap;
    }

    /**
     * Make a HashMap populated with the given key and value.
     * @param key
     * @param value
     * @return
     */
    HashMap<String, String> newMap(String key, String value)
    {
    	HashMap<String, String> map = new HashMap<String, String>();
    	map.put(key, value);
    	return map;
    }

    HashMap<String, String> newMap(String key, String value, String key2, String value2)
    {
    	HashMap<String, String> map = newMap(key, value);
    	map.put(key2, value2);
    	return map;
    }
    
    /**
     *  Make association property metadata for the entity.
     *  Also populates the _fkMap which is used for related-entity fixup when saving.
     * @param containingType
     * @param propType
     * @param propName
     * @param columnNames
     * @param pClass
     * @param relatedDataPropertyMap
     * @param isKey
     * @return association property definition
     */
    private HashMap<String, Object> makeAssociationProperty(Class containingType, AssociationType propType, String propName, String columnNames, PersistentClass pClass, HashMap<String, HashMap<String, Object>> relatedDataPropertyMap, boolean isKey)
    {
        HashMap<String, Object> nmap = new LinkedHashMap<String, Object>();
        nmap.put("nameOnServer", propName);

        Class relatedEntityType = getEntityType(propType);
        nmap.put("entityTypeName", getEntityTypeName(relatedEntityType));
        nmap.put("isScalar", !propType.isCollectionType());

        // the associationName must be the same at both ends of the association.
        nmap.put("associationName", getAssociationName(containingType.getSimpleName(), relatedEntityType.getSimpleName(), (propType instanceof OneToOneType)));

        // The foreign key columns usually applies for many-to-one and one-to-one associations
        if (!propType.isCollectionType())
        {
            String entityRelationship = pClass.getEntityName() + '.' + propName;
            HashMap<String, Object> relatedDataProperty = relatedDataPropertyMap.get(columnNames);
            if (relatedDataProperty != null)
            {
                String fkName = (String) relatedDataProperty.get("nameOnServer");
                nmap.put("foreignKeyNamesOnServer", new String[] { fkName });
                _fkMap.put(entityRelationship, fkName);
                if (isKey)
                {
                    if (!relatedDataProperty.containsKey("isPartOfKey"))
                    {
                        relatedDataProperty.put("isPartOfKey", true);
                    }
                }
            }
            else
            {
                nmap.put("foreignKeyNamesOnServer", columnNames);
                nmap.put("ERROR", "Could not find matching fk for property " + entityRelationship);
                _fkMap.put(entityRelationship, columnNames);
                throw new IllegalArgumentException("Could not find matching fk for property " + entityRelationship);
            }
        }
        return nmap;
    }
    
    /**
     * Get the type name in the form "Order:#northwind.model"
     * @param clazz
     * @return
     */
    String getEntityTypeName(Class clazz)
    {
    	return clazz.getName();
//    	return clazz.getSimpleName() + ":#" + clazz.getPackage().getName();
    }

    /**
     *  Get the column names for a given property as a comma-delimited String of unbracketed, lowercase names.
     */
    String getPropertyColumnNames(AbstractEntityPersister persister, String propertyName)
    {
        String propColumnNames[] = persister.getPropertyColumnNames(propertyName);
        if (propColumnNames.length == 0)
        {
            // this happens when the property is part of the key
            propColumnNames = persister.getKeyColumnNames();
        }
        StringBuilder sb = new StringBuilder();
        for (String s : propColumnNames)
        {
            if (sb.length() > 0) sb.append(',');
            sb.append(unBracket(s));
        }
        return sb.toString().toLowerCase();
    }

    /**
     *  Get the column name without square brackets or quotes around it.  E.g. "[OrderID]" -> OrderID 
     *  Because sometimes Hibernate gives us brackets, and sometimes it doesn't.
     *  Double-quotes happen with SQL CE.
     */
    String unBracket(String name)
    {
        name = (name.charAt(0) == '[') ? name.substring(1, name.length() - 1) : name;
        name = (name.charAt(0) == '"') ? name.substring(1, name.length() - 1) : name;
        return name;
    }

    /**
     *  Get the Breeze name of the entity type.
     *  For collections, Breeze expects the name of the element type.
     * @param propType
     * @return
     */
    Class getEntityType(AssociationType propType)
    {
    	if (!propType.isCollectionType()) return propType.getReturnedClass();
    	CollectionType collType = (CollectionType) propType;
    	
    	Type elementType = collType.getElementType((SessionFactoryImplementor) _sessionFactory);
    	return elementType.getReturnedClass();
    }
    

    /**
     *  Lame pluralizer.  Assumes we just need to add a suffix.
     */
    String pluralize(String s)
    {
        if (s == null || s.isEmpty()) return s;
        int last = s.length() - 1;
        char c = s.charAt(last);
        switch (c)
        {
            case 'y':
                return s.substring(0, last) + "ies";
            default:
                return s + 's';
        }
    }

    /**
     *  Creates an association name from two entity names.
     *  For consistency, puts the entity names in alphabetical order.
     * @param name1
     * @param name2
     * @param isOneToOne - if true, adds the one-to-one suffix
     * @return
     */
    String getAssociationName(String name1, String name2, boolean isOneToOne)
    {
        if (name1.compareTo(name2) < 0)
            return ASSN + name1 + '_' + name2 + (isOneToOne ? ONE2ONE : "");
        else
            return ASSN + name2 + '_' + name1 + (isOneToOne ? ONE2ONE : "");
    }
    static final String ONE2ONE = "_1to1";
    static final String ASSN = "AN_";

    // Map of NH datatype to Breeze datatype.
    static HashMap<String, String> BreezeTypeMap;

    // Map of data type to Breeze validation type
    static HashMap<String, String> ValidationTypeMap;
    
    static 
    {
        BreezeTypeMap = new HashMap<String, String>();
        BreezeTypeMap.put("byte[]", "Binary");
        BreezeTypeMap.put("binaryBlob", "Binary");
        BreezeTypeMap.put("blob", "Binary");
        BreezeTypeMap.put("timestamp", "DateTime");
        BreezeTypeMap.put("TimeAsTimeSpan", "Time");

        ValidationTypeMap = new HashMap<String, String>();
        ValidationTypeMap.put("Boolean", "bool");
        ValidationTypeMap.put("Byte", "byte");
        ValidationTypeMap.put("DateTime", "date");
        ValidationTypeMap.put("DateTimeOffset", "date");
        ValidationTypeMap.put("BigDecimal", "number");
        ValidationTypeMap.put("Guid", "guid");
        ValidationTypeMap.put("UUID", "guid");
        ValidationTypeMap.put("Short", "int16");
        ValidationTypeMap.put("Integer", "int32");
        ValidationTypeMap.put("Long", "integer");
        ValidationTypeMap.put("Float", "number");
        ValidationTypeMap.put("Time", "duration");
        ValidationTypeMap.put("TimeAsTimeSpan", "duration");
        
    }



    
}
