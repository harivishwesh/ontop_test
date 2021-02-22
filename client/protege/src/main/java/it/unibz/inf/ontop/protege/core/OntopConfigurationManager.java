package it.unibz.inf.ontop.protege.core;


import it.unibz.inf.ontop.injection.OntopSQLOWLAPIConfiguration;
import it.unibz.inf.ontop.protege.connection.DataSource;
import org.semanticweb.owlapi.model.OWLOntology;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.Properties;

import static it.unibz.inf.ontop.injection.OntopSQLCoreSettings.*;
import static it.unibz.inf.ontop.injection.OntopSQLCredentialSettings.*;

/**
 * TODO: find a better name
 */
public class OntopConfigurationManager {

    public static final String PROPERTY_EXT = ".properties"; // The default property file extension.
    public static final String DBPREFS_EXT = ".db_prefs"; // The default db_prefs (currently only user constraints) file extension.
    public static final String DBMETADATA_EXT = ".json"; // The default db-metadata file extension.

    private final OBDAModelManager obdaModelManager;
    private final Properties settings = new Properties();
    private final Properties userSettings = new Properties();

    @Nullable
    private File implicitDBConstraintFile;

    @Nullable
    private File dbMetadataFile;

    OntopConfigurationManager(@Nonnull OBDAModelManager obdaModelManager, @Nonnull DisposableProperties internalSettings) {
        this.obdaModelManager = obdaModelManager;
        this.settings.putAll(internalSettings);
        this.implicitDBConstraintFile = null;
        this.dbMetadataFile = null;
    }

    public void reset(DisposableProperties settings) {
        this.implicitDBConstraintFile = null;
        this.dbMetadataFile = null;

        this.settings.clear();
        this.settings.putAll(settings);
        this.userSettings.clear();

        obdaModelManager.getDatasource().set("", "", "", "");
    }

    public void loadNewConfiguration(String owlName) throws IOException {
        File implicitDBConstraintFile = new File(URI.create(owlName + DBPREFS_EXT));
        if (implicitDBConstraintFile.exists())
            this.implicitDBConstraintFile = implicitDBConstraintFile;

        File dbMetadataFile = new File(URI.create(owlName + DBMETADATA_EXT));
        if(dbMetadataFile.exists())
            this.dbMetadataFile = dbMetadataFile;

        File propertyFile = new File(URI.create(owlName + PROPERTY_EXT));
        if (propertyFile.exists()) {
            userSettings.load(new FileReader(propertyFile));
            copyDataSourceParametersFromUserSettings();
        }
    }

    Properties snapshotProperties() {
        Properties properties = new Properties();
        properties.putAll(settings);
        properties.putAll(userSettings);
        properties.putAll(obdaModelManager.getDatasource().asProperties());
        return properties;
    }

    Properties snapshotUserProperties() {
        Properties properties = new Properties();
        properties.putAll(userSettings);
        properties.putAll(obdaModelManager.getDatasource().asProperties());
        return properties;
    }

    public OntopSQLOWLAPIConfiguration buildOntopSQLOWLAPIConfiguration(OWLOntology currentOntology) {

        OntopSQLOWLAPIConfiguration.Builder<?> builder = OntopSQLOWLAPIConfiguration.defaultBuilder()
                .properties(snapshotProperties())
                .ppMapping(obdaModelManager.getTriplesMapCollection().generatePPMapping());

        Optional.ofNullable(implicitDBConstraintFile)
                .ifPresent(builder::basicImplicitConstraintFile);

        Optional.ofNullable(dbMetadataFile)
                .ifPresent(builder::dbMetadataFile);

        builder.ontology(currentOntology);

        return builder.build();
    }

    /**
     * Loads the properties in the global settings and in data source.
     */
    public void loadProperties(Properties properties) {
        userSettings.putAll(properties);
        copyDataSourceParametersFromUserSettings();
    }

    private void copyDataSourceParametersFromUserSettings() {
        DataSource datasource = obdaModelManager.getDatasource();
        datasource.set(
                Optional.ofNullable(userSettings.getProperty(JDBC_URL))
                        .orElseGet(datasource::getURL),
                Optional.ofNullable(userSettings.getProperty(JDBC_USER))
                        .orElseGet(datasource::getUsername),
                Optional.ofNullable(userSettings.getProperty(JDBC_PASSWORD))
                        .orElseGet(datasource::getPassword),
                Optional.ofNullable(userSettings.getProperty(JDBC_DRIVER))
                        .orElseGet(datasource::getDriver));
    }
}
