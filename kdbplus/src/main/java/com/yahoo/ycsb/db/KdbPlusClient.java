package com.yahoo.ycsb.db;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import kx.c;
import kx.c.KException;

/**
 * kdb+ client for YCSB framework. Insertions will be stored on memory, so after
 * shutdown data is lost.
 * 
 * @author Rene Trefft
 * 
 */
public class KdbPlusClient extends DB {

	private String ip;
	private int port;

	private boolean test;
	private final boolean _DEBUG = false;

	private final int SUCCESS = 0;

	private c kdbPlus;

	@Override
	public void init() throws DBException {

		test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));

		if (!getProperties().containsKey("port") && !test) {
			throw new DBException("No port given, abort.");
		}
		port = Integer.parseInt(getProperties().getProperty("port", "5001"));

		if (!getProperties().containsKey("ip") && !test) {
			throw new DBException("No ip given, abort.");
		}
		ip = getProperties().getProperty("ip", "localhost");

		if (_DEBUG) {
			System.out.println("The following properties are given: ");
			for (String element : getProperties().stringPropertyNames()) {
				System.out.println(element + ": " + getProperties().getProperty(element));
			}
		}

		if (!test) {
			try {
				// establishes connection to kdb+
				this.kdbPlus = new c(ip, port);
			} catch (KException | IOException e) {
				throw new DBException(e);

			}
		}

	}

	@Override
	public void cleanup() throws DBException {
		try {
			kdbPlus.close();
		} catch (IOException e) {
			throw new DBException(e);
		}
	}

	@Override
	public int read(String metric, Timestamp timestamp, HashMap<String, ArrayList<String>> tags) {

		if (metric == null || metric == "" || timestamp == null) {
			return -1;
		}

		StringBuilder query = new StringBuilder("select from ");
		query.append(metric);
		query.append(" where(timestamp=\"P\"$\"");
		query.append(timestamp.toString());
		query.append("\")");

		generateTagFilterQueryPart(query, tags);

		if (_DEBUG) {
			System.out.println("Query: " + query);
		}

		if (test) {
			return SUCCESS;
		}

		return execQuery(query.toString());

	}

	@Override
	public int scan(String metric, Timestamp startTs, Timestamp endTs, HashMap<String, ArrayList<String>> tags,
			boolean avg, boolean count, boolean sum, int timeValue, TimeUnit timeUnit) {

		if (metric == null || metric.isEmpty() || startTs == null || endTs == null) {
			return -1;
		}

		StringBuilder query = new StringBuilder("select ");

		if (avg) {
			query.append("avg ");
		} else if (count) {
			query.append("count ");
		} else if (sum) {
			query.append("sum ");
		}

		// Resolution / down sampling is not supported by kdb+ out-of-the-box.
		// We use the built-in xbar function which returns in the given time
		// range for example the average for every 1 second.

		query.append("val by(");
		query.append(timeValue);

		if (timeUnit != TimeUnit.NANOSECONDS) {

			query.append("*");

			if (timeUnit == TimeUnit.MICROSECONDS) {
				query.append("1000");
			} else if (timeUnit == TimeUnit.MILLISECONDS) {
				query.append("1000000");
			} else if (timeUnit == TimeUnit.SECONDS) {
				query.append("1000000000");
			} else if (timeUnit == TimeUnit.MINUTES) {
				query.append("60000000000");
			} else if (timeUnit == TimeUnit.HOURS) {
				query.append("3600000000000");
			} else if (timeUnit == TimeUnit.DAYS) {
				query.append("86400000000000");
			} else {
				return -1;
			}

		}

		query.append(")xbar timestamp");

		query.append(" from ");
		query.append(metric);

		query.append(" where(timestamp>=\"P\"$\"");
		query.append(startTs.toString());
		query.append("\")");
		query.append(",(timestamp<=\"P\"$\"");
		query.append(endTs.toString());
		query.append("\")");

		generateTagFilterQueryPart(query, tags);

		if (_DEBUG) {
			System.out.println("Query: " + query);
		}

		if (test) {
			return SUCCESS;
		}

		return execQuery(query.toString());

	}

	/**
	 * 
	 * Sends {@code query} to the kdb+ instance which executes it.
	 * 
	 * @param query
	 * 
	 */
	private int execQuery(String query) {

		try {
			kdbPlus.k(query);
			return SUCCESS;
		} catch (KException | IOException e) {
			e.printStackTrace();
			return -1;
		}

	}

	/**
	 * 
	 * Generates the tag filter query part. The query part will be appended to
	 * StringBuilder {@code query}.
	 * 
	 * @param query
	 * @param tags
	 *            to use for filtering
	 */
	private void generateTagFilterQueryPart(StringBuilder query, HashMap<String, ArrayList<String>> tags) {

		for (Map.Entry<String, ArrayList<String>> tag : tags.entrySet()) {

			query.append(',');

			for (String tagValue : tag.getValue()) {

				query.append("(tags[;`$\"");
				// We assume that the tag keys used in the queries are defined
				// in all inserted rows, more precisely in their tags
				// dictionary. Otherwise a type error is thrown and we have to
				// use the match operator.
				// See: http://stackoverflow.com/questions/33989718/select-query-on-kdb-tsdb-table-with-nested-dictionary
				query.append(tag.getKey());
				query.append("\"]=`$\"");
				query.append(tagValue);
				query.append("\")or");

			}

			// deletes the "or" after the last value of the actual tag
			deleteLastChars(query, 2);

		}

	}

	/**
	 * 
	 * Generates a query part which creates a table with name {@code table} if
	 * there is not already a table with that name. The query part will be
	 * appended to StringBuilder {@code query}.
	 * 
	 * @param query
	 * @param table
	 *            name - must only consists of letters, numbers and underscores
	 *            (case-sensitive), no whitespaces are allowed
	 */
	private void generateCreateTableIfNotExistQueryPart(StringBuilder query, String table) {
		query.append("if[not`");
		query.append(table);
		query.append(" in tables[];");
		query.append(table);
		query.append(":([]timestamp:-12h$();val:-9h$();tags:())];");
	}

	/**
	 * @param sb
	 *            to delete chars from
	 * @param numChars
	 *            to delete at the end
	 */
	private void deleteLastChars(StringBuilder sb, int numChars) {
		sb.delete(sb.length() - numChars, sb.length());
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * {@code metric} must only consists of letters, numbers and underscores
	 * (case-sensitive), no whitespaces are allowed.
	 */
	@Override
	public int insert(String metric, Timestamp timestamp, double value, HashMap<String, ByteIterator> tags) {

		if (metric == null || metric.isEmpty() || timestamp == null) {
			return -1;
		}

		StringBuilder query = new StringBuilder();

		generateCreateTableIfNotExistQueryPart(query, metric);

		query.append('`');
		query.append(metric);
		query.append(" insert(\"P\"$\"");
		query.append(timestamp.toString());
		query.append("\";");
		query.append(value);
		query.append(';');

		int numTags = tags.size();

		if (numTags > 1) {

			StringBuilder tagKeysQueryPart = new StringBuilder("(`$(");
			StringBuilder tagValuesQueryPart = new StringBuilder("`$(");

			for (Map.Entry<String, ByteIterator> tag : tags.entrySet()) {
				tagKeysQueryPart.append('"');
				tagKeysQueryPart.append(tag.getKey());
				tagKeysQueryPart.append("\";");
				tagValuesQueryPart.append('"');
				tagValuesQueryPart.append(tag.getValue());
				tagValuesQueryPart.append("\";");
			}

			// deletes the last ";"
			deleteLastChars(tagKeysQueryPart, 1);
			deleteLastChars(tagValuesQueryPart, 1);

			tagKeysQueryPart.append("))");
			tagValuesQueryPart.append("))");

			query.append(tagKeysQueryPart);
			query.append('!');
			query.append(tagValuesQueryPart);

		} else if (numTags == 1) {
			// prefix 'enlist' is necessary for dict with one key/value
			query.append("(enlist`$\"");
			Entry<String, ByteIterator> tag = tags.entrySet().iterator().next();
			query.append(tag.getKey());
			query.append("\")!enlist`$\"");
			query.append(tag.getValue());
			query.append("\")");

		} else {
			// char '-' as tags => no tags are defined
			query.append("-)");

		}

		if (_DEBUG) {
			System.out.println("Query: " + query);
		}

		if (test) {
			return SUCCESS;
		}

		return execQuery(query.toString());

	}

}
