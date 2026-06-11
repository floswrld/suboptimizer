<<<<<<< HEAD
# suboptimizer
=======
# Subscription & Fixed-Cost Optimizer

Maven project for the Database Systems II pre-exam project (Part 1, no AI).
Java 17 + Swing GUI + JDBC, talking to a MySQL 8.0 database.

## 1. Set up the database

Run the SQL script once in MySQL (Workbench or command line):

    mysql -u root -p < src/main/resources/schema.sql

This creates the database `subopt` with all tables, sample data,
triggers and stored procedures.

## 2. Adjust the connection settings

Open `src/main/java/de/hft/suboptimizer/SubOptApp.java` and edit the
three constants near the top if needed:

    private static final String URL  = "jdbc:mysql://localhost:3306/subopt?serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASS = "";   // <-- your MySQL password

For the HFT LIDA server, replace `localhost` with the server host name.

## 3a. Run in Eclipse

- File > Import > Maven > Existing Maven Projects, pick this folder.
- Right-click the project > Maven > Update Project (Alt+F5).
- Right-click SubOptApp.java > Run As > Java Application.

## 3b. Run from the command line

    mvn compile exec:java

The MySQL driver is pulled in automatically by Maven, no manual JAR needed.

## What you should see

The window opens with a user drop-down (Anna, Ben, Clara).
- "Show subscriptions" lists the selected user's active subscriptions.
- "Find wasted overlap" shows, for Anna, that Spotify overlaps with
  Apple Music in the Music Streaming category.
- "Cancel selected" cancels the highlighted subscription (transactionally).
>>>>>>> ad56f3c (added project files)
