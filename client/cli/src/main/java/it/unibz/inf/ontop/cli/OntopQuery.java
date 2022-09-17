package it.unibz.inf.ontop.cli;

import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;
import com.github.rvesse.airline.annotations.OptionType;
import com.github.rvesse.airline.annotations.help.BashCompletion;
import com.github.rvesse.airline.annotations.restrictions.Required;
import com.github.rvesse.airline.help.cli.bash.CompletionBehaviour;
import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.injection.OntopSQLOWLAPIConfiguration;
import it.unibz.inf.ontop.owlapi.OntopOWLEngine;
import it.unibz.inf.ontop.owlapi.connection.OWLConnection;
import it.unibz.inf.ontop.owlapi.connection.OWLStatement;
import it.unibz.inf.ontop.owlapi.impl.SimpleOntopOWLEngine;
import it.unibz.inf.ontop.owlapi.resultset.OWLBindingSet;
import it.unibz.inf.ontop.owlapi.resultset.TupleOWLResultSet;
import org.semanticweb.owlapi.io.ToStringRenderer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static java.util.stream.Collectors.joining;

@Command(name = "query",
        description = "Query the RDF graph exposed by the mapping and the OWL ontology")
public class OntopQuery extends OntopReasoningCommandBase {

    @Option(type = OptionType.COMMAND, name = {"-q", "--query"}, title = "queryFile",
            description = "SPARQL SELECT query file")
    @BashCompletion(behaviour = CompletionBehaviour.FILENAMES)
    @Required
    private String queryFile;

    public OntopQuery() {
    }

    @Override
    public void run() {

        OntopSQLOWLAPIConfiguration.Builder<?> configurationBuilder = OntopSQLOWLAPIConfiguration.defaultBuilder()
                .enableOntologyAnnotationQuerying(enableAnnotations);

        if (owlFile != null)
            configurationBuilder.ontologyFile(owlFile);

        if (propertiesFile != null) {
            configurationBuilder.propertyFile(propertiesFile);
        }

        if (isR2rmlFile(mappingFile)) {
            configurationBuilder.r2rmlMappingFile(mappingFile);
        } else {
            configurationBuilder.nativeOntopMappingFile(mappingFile);
        }

        if (dbMetadataFile != null)
            configurationBuilder.dbMetadataFile(dbMetadataFile);

        if (ontopViewFile != null)
            configurationBuilder.ontopViewFile(ontopViewFile);

        if (dbPassword != null)
            configurationBuilder.jdbcPassword(dbPassword);

        if (dbUrl != null)
            configurationBuilder.jdbcUrl(dbUrl);

        if (dbUser != null)
            configurationBuilder.jdbcUser(dbUser);

        if (dbDriver != null)
            configurationBuilder.jdbcDriver(dbDriver);

        try (OntopOWLEngine engine = new SimpleOntopOWLEngine(configurationBuilder.build());
             OWLConnection conn = engine.getConnection();
             OWLStatement st = conn.createStatement();
        ) {

			/*
             * Reading query file:
			 */
//            String query = Joiner.on("\n").
//                    join(Files.readAllLines(Paths.get(queryFile), StandardCharsets.UTF_8));

            String query = Files.lines(Paths.get(queryFile), StandardCharsets.UTF_8).collect(joining("\n"));

            TupleOWLResultSet result = st.executeSelectQuery(query);

            OutputStream out = null;
            if (outputFile == null) {
                out = System.out;
            } else {
                out = new FileOutputStream(new File(outputFile));
            }
            printResult(out, result);


        } catch (Exception e1) {
            e1.printStackTrace();

        }
    }

    public static void printResult(OutputStream out, TupleOWLResultSet result) throws Exception {
        BufferedWriter wr = new BufferedWriter(new OutputStreamWriter(out, "utf8"));

		/*
         * Printing the header
		 */
        List<String> signature = result.getSignature();

        int columns = result.getColumnCount();
        for (int c = 0; c < columns; c++) {
            String value = signature.get(c);
            wr.append(value);
            if (c + 1 < columns)
                wr.append(",");
        }
        wr.newLine();

        while (result.hasNext()) {
            final OWLBindingSet bindingSet = result.next();
            ImmutableList.Builder<String> valueListBuilder = ImmutableList.builder();
            for (String columnName : signature) {
                // TODO: make it robust to NULLs
                valueListBuilder.add(ToStringRenderer.getInstance().getRendering(bindingSet.getOWLObject(columnName)));
            }
            wr.append(String.join(",", valueListBuilder.build()));
            wr.newLine();
        }
        wr.flush();

        result.close();
    }


}
