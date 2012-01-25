package edu.umd.cs.findbugs.flybush;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.Maps;

public class UsageConsolidatorServlet extends AbstractFlybushServlet {

    public static final int CONSOLIDATION_DATA_VERSION = 1;
    public static final DateFormat DATE_FORMAT = DateFormat.getDateInstance(DateFormat.SHORT, Locale.ENGLISH);

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        String uri = req.getRequestURI();
        PersistenceManager pm = getPersistenceManager();
        try {
            if (uri.equals("/consolidate-usage")) {
                String dateStr = req.getParameter("date");
                if (dateStr == null || dateStr.equals("")) {
                    startConsolidating(resp);
                } else {
                    consolidateDate(req, resp, pm, dateStr);
                }

            } else {
                LOGGER.warning("Unknown request: " + uri);
                show404(resp);
            }
        } finally {
            pm.close();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doGet(req, resp);
    }

    @Override
    protected void handlePost(PersistenceManager pm, HttpServletRequest req, HttpServletResponse resp, String uri)
            throws IOException {
    }

    private Date dayStart(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private void consolidateDate(HttpServletRequest req, HttpServletResponse resp, PersistenceManager pm, String dateStr) 
            throws IOException {
        LOGGER.info("Consolidating " + dateStr);
        Date dateDate;
        try {
            dateDate = dayStart(DATE_FORMAT.parse(dateStr));
        } catch (ParseException e) {
            show404(resp);
            return;
        }

        Query vquery = pm.newQuery("select from " + persistenceHelper.getDbUsageSummaryClassname() 
                + " where date == :date && category == 'consolidation-data-version' && value == :ver");
        @SuppressWarnings("unchecked") 
        List<DbUsageSummary> ventries = (List<DbUsageSummary>) vquery.execute(dateDate, CONSOLIDATION_DATA_VERSION);
        if (!ventries.isEmpty()) {
            DbUsageSummary summary = ventries.iterator().next();
            String lastUpdatedStr = DateFormat.getDateTimeInstance().format(summary.getLastUpdated());
            int version = summary.getValue();
            String msg = "Skipping - already at version " + version + " - last updated " + lastUpdatedStr;
            LOGGER.info(msg);
            setResponse(resp, 200, msg);
            return;
        }

        Query query = pm.newQuery("select from " + persistenceHelper.getDbUsageEntryClassname()
                + " where date >= :startDate && date < :endDate");
        query.addExtension("javax.persistence.query.chunkSize", 200);
        @SuppressWarnings("unchecked")
        List<DbUsageEntry> entries = (List<DbUsageEntry>) query.execute(dateDate, nextDay(dateDate));

        UsageDataConsolidator data = new UsageDataConsolidator(persistenceHelper);
        data.process(query, entries);
        LOGGER.info("Storing consolidated data...");

        for (DbUsageSummary summary : data.createSummaryEntries(dateDate)) {
            commit(pm, summary);
        }

        setResponse(resp, 200, "Done!");
    }

    private Date nextDay(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DATE, 1);
        return cal.getTime();
    }

    private static void commit(PersistenceManager pm, DbUsageSummary entry) {
        pm.currentTransaction().begin();
        try {
            pm.makePersistent(entry);
            pm.currentTransaction().commit();
        } finally {
            if (pm.currentTransaction().isActive())
                pm.currentTransaction().rollback();
        }
    }

    /*
   Important stats:
   - per day:
     - users by version, stacked
   - past month:
     - users by country
     - users by os
     - users by java version
     - users by language
   - plugin usage
     - drill-down: plugin versions
    */

    private void startConsolidating(HttpServletResponse resp) throws IOException {
        LOGGER.info("About to execute query");
        Calendar cal = yesterdayMidnight();
        //TODO: change back to six months!
        int SIX_MONTHS = 30;
        for (int i = 0; i < SIX_MONTHS; i++) {
            Map<String, String> parameters = Maps.newHashMap();
            parameters.put("date", DATE_FORMAT.format(cal.getTime()));
            persistenceHelper.addToQueue("/consolidate-usage", parameters);
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }

        setResponse(resp, 200, "Done");
    }

    private Calendar yesterdayMidnight() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, -1);
        return cal;
    }

}
