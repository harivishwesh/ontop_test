package it.unibz.krdb.obda.parser;

import it.unibz.krdb.sql.api.ColumnReference;
import it.unibz.krdb.sql.api.GroupingElement;

import java.util.ArrayList;
import java.util.List;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;

public class AggregationJSQL {

	
private static final long serialVersionUID = 5806057160397315905L;
	
	/**
	 * Collection of grouping columns. Uses {@link GroupingElement}
	 * as the grouping unit. Each grouping unit can contain
	 * one or several {@link ColumnReference}.
	 */
	private List<Expression> groupingList;
	
	public AggregationJSQL() {
		groupingList = new ArrayList<Expression>();
	}
	
	
	
	
	
	/**
	 * Inserts a grouping element to the list. Use this method
	 * if users can define this object already.
	 * 
	 * @param group
	 * 			The grouping element.
	 */
	public void add(Expression group) {
		groupingList.add(group);
	}
	
	/**
	 * Appends several grouping elements to the list.
	 * 
	 * @param list
	 * 			The list of grouping elements.
	 */
	public void addAll(List<Expression> list) {
		groupingList.addAll(list);
	}
	
	/**
	 * Updates the column list in this aggregation. Any existing
	 * grouping elements are replaced by the new list.
	 * 
	 * @param columns
	 * 			The new grouping element list.
	 */
	public void update(ArrayList<Expression> groups) {
		groupingList.clear();
		addAll(groups);
	}
	
	@Override
	public String toString() {
		String str = "";
		if(!groupingList.isEmpty())
		str = "group by";
		
		boolean bNeedComma = false;
		for (Expression group : groupingList) {
			if (bNeedComma) {
				str += ",";
			}
			str += " ";
			str += group.toString();
			bNeedComma = true;
		}
		return str;
	}
}
