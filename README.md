# Subscription & Fixed-Cost Optimizer

Maven project for the Database Systems II pre-exam project (Part 1, no AI).
Java 17 + Swing GUI + JDBC against the MySQL database `dk6sp_subopt`
on the HFT lab server.

## Connection

The connection constants in `SubOptApp.java` are preconfigured:
host 193.196.143.168 (HFT database server), user dk6s_12vafl1bif,
database dk6sp_subopt. The HFT VPN must be active, since the database
server is only reachable from inside the university network. If port
3306 is not reachable even with VPN, open an SSH tunnel through the
LIDA server first and set HOST to 127.0.0.1:

    ssh -L 3306:193.196.143.168:3306 12vafl1bif@lida.fkc.hft-stuttgart.de

## Load the schema onto the server

The script `src/main/resources/schema.sql` creates all tables, sample
data, both triggers and both stored procedures inside `dk6sp_subopt`.
It starts by dropping any existing objects, so it can be re-run at any
time to reset the database to a clean demo state.

Command line:

    mysql -h 193.196.143.168 -u dk6s_12vafl1bif -p < src/main/resources/schema.sql

Or open the file in MySQL Workbench (connected to the lab server) and
execute it as a whole; the DELIMITER blocks for the triggers and
procedures require running the full script, not statement by statement.

## Run the application

    mvn compile exec:java

Or in Eclipse: import as existing Maven project, then run
`SubOptApp.java` as Java Application.

## What you should see

The window opens with a user drop-down (Anna, Ben, Clara).

- "Show subscriptions" lists the selected user's active subscriptions
  (JOIN over subscription, price_tier, service).
- The monthly spend label is computed by the stored procedure
  `sp_monthly_spend` (yearly tiers normalised to price/12).
- "Find wasted overlap" runs the self-join detecting two services in
  the same category; for Anna it reports Spotify vs Apple Music.
- "Cancel selected" calls `sp_cancel_subscription`, which runs as a
  transaction (UPDATE + audit INSERT into notification) with
  COMMIT and a ROLLBACK handler.

Triggers: `trg_check_renewal` rejects inserts where the renewal date
lies before the start date; `trg_trial_reminder` writes a notification
when a trial subscription ending within 3 days is inserted.

Note for the demo: the sample data uses fixed dates around early June
2026; the trial rows only produce reminder notifications if inserted
while their trial_end_date is at most 3 days away.
