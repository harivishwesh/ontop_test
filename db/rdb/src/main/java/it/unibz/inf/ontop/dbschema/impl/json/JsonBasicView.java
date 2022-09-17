package it.unibz.inf.ontop.dbschema.impl.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.*;
import it.unibz.inf.ontop.dbschema.*;
import it.unibz.inf.ontop.exception.MetadataExtractionException;

import javax.annotation.Nonnull;
import java.util.*;

@JsonDeserialize(as = JsonBasicView.class)
public class JsonBasicView extends JsonBasicOrJoinView {

    @Nonnull
    public final List<String> baseRelation;

    @JsonCreator
    public JsonBasicView(@JsonProperty("columns") JsonBasicOrJoinView.Columns columns, @JsonProperty("name") List<String> name,
                         @JsonProperty("baseRelation") List<String> baseRelation,
                         @JsonProperty("filterExpression") String filterExpression,
                         @JsonProperty("uniqueConstraints") UniqueConstraints uniqueConstraints,
                         @JsonProperty("otherFunctionalDependencies") OtherFunctionalDependencies otherFunctionalDependencies,
                         @JsonProperty("foreignKeys") ForeignKeys foreignKeys,
                         @JsonProperty("nonNullConstraints") NonNullConstraints nonNullConstraints) {
        super(name, uniqueConstraints, otherFunctionalDependencies, foreignKeys, nonNullConstraints, columns, filterExpression);
        this.baseRelation = baseRelation;
    }

    @Override
    protected ImmutableList<ParentDefinition> extractParentDefinitions(DBParameters dbParameters,
                                                                                     MetadataLookup parentCacheMetadataLookup)
            throws MetadataExtractionException {
        QuotedIDFactory quotedIDFactory = dbParameters.getQuotedIDFactory();
        NamedRelationDefinition parentDefinition = parentCacheMetadataLookup.getRelation(quotedIDFactory.createRelationID(
                baseRelation.toArray(new String[0])));

        return ImmutableList.of(new ParentDefinition(parentDefinition,""));
    }

    /**
     * TODO: support duplication of the column (not just renamings)
     */
    @Override
    public ImmutableList<ImmutableList<Attribute>> getAttributesIncludingParentOnes(OntopViewDefinition ontopViewDefinition,
                                                                                    ImmutableList<Attribute> parentAttributes) {
        if (filterExpression != null && (!filterExpression.isEmpty()))
            // TODO: log a warning
            return ImmutableList.of();

        return getDerivedFromParentAttributes(ontopViewDefinition, parentAttributes);
    }
}
