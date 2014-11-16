package it.unibz.krdb.obda.owlrefplatform.core.mappingprocessing;


import it.unibz.krdb.obda.ontology.OClass;
import it.unibz.krdb.obda.ontology.PropertyExpression;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Class for TMapping Optimization. The Mappings for the classes and properties in the configuration are assumed to "exact".
 * Therefore, the TMappings for those predicates are not needed to be generated by
 * {@see it.unibz.krdb.obda.owlrefplatform.core.mappingprocessing.TMappingProcessor}
 */
public class TMappingExclusionConfig {

    private final Set<String> classes;

    private final Set<String> properties;

    public TMappingExclusionConfig(Set<String> classes, Set<String> properties){
        this.classes = classes;
        this.properties = properties;
    }

    public boolean contains(OClass cls){
        return classes.contains(cls.getPredicate().getName());
    }

    public boolean contains(PropertyExpression propertyExpression){
        return !propertyExpression.isInverse() && properties.contains(propertyExpression.getPredicate().getName());
    }

    private static final TMappingExclusionConfig EMPTY = new TMappingExclusionConfig(Collections.<String>emptySet(), Collections.<String>emptySet());

    /**
     * @return a default empty configuration
     */
    public static TMappingExclusionConfig empty(){
        return EMPTY;
    }

    public static TMappingExclusionConfig parseFile(String fileName) throws IOException {
        Set<String> classes = new HashSet<>();
        Set<String> properties = new HashSet<>();

        try (BufferedReader in = new BufferedReader(new FileReader(fileName))) {
            String s;

            while ((s = in.readLine()) != null) {

                s = s.trim();
                // empty line or comments
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                String separator = " ";
                String[] s2 = s.split("\\" + separator);

                if (s2.length != 2) {
                    throw new IllegalArgumentException("cannot parse line (too many columns): " + s);
                }

                try {
                    int arity = Integer.parseInt(s2[1]);
                    if (arity == 1) {
                        classes.add(s2[0]);
                    } else if (Integer.parseInt(s2[1]) == 2) {
                        properties.add(s2[0]);
                    } else {
                        throw new IllegalArgumentException("cannot parse line (wrong arity): " + s);
                    }
                } catch (NumberFormatException e){
                    throw new IllegalArgumentException("cannot parse line (wrong arity): " + s);
                }
            }
        }

        TMappingExclusionConfig conf = new TMappingExclusionConfig(classes, properties);

        return conf;

    }
}
