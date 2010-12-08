PRAGMA foreign_keys = ON;

CREATE TABLE RUN
(
ID INTEGER PRIMARY KEY ASC AUTOINCREMENT
);

CREATE TABLE BENCHMARK
(
ID INTEGER PRIMARY KEY ASC,
SUITE VARCHAR,
NAME VARCHAR,
DESCRIPTION VARCHAR,
CTG_ID VARCHAR
);

CREATE TABLE NOC_TOPOLOGY
(
ID INTEGER PRIMARY KEY ASC,
NAME VARCHAR,
DESCRIPTION VARCHAR,
SIZE VARCHAR
);

CREATE TABLE PARAMETER
(
ID INTEGER,
NAME VARCHAR,
VALUE VARCHAR,
PRIMARY KEY (ID, NAME, VALUE),
FOREIGN KEY(ID) REFERENCES RUN(ID)
);

CREATE TABLE OUTPUT
(
ID INTEGER,
NAME VARCHAR,
VALUE VARCHAR,
PRIMARY KEY (ID, NAME, VALUE),
FOREIGN KEY(ID) REFERENCES RUN(ID)
);

CREATE TABLE MAPPER
(
ID INTEGER PRIMARY KEY ASC,
NAME VARCHAR,
PARAMETER INTEGER,
DESCRIPTION VARCHAR,
BENCHMARK INTEGER,
APCG_ID VARCHAR,
NOC_TOPOLOGY INTEGER,
MAPPING_XML CLOB,
OUTPUT INTEGER,
START_DATETIME TEXT,
REAL_TIME REAL,
USER_TIME REAL,
SYS_TIME REAL,
MEMORY REAL,
RUN INTEGER,
FOREIGN KEY(BENCHMARK) REFERENCES BENCHMARK(ID),
FOREIGN KEY(NOC_TOPOLOGY) REFERENCES NOC_TOPOLOGY(ID),
FOREIGN KEY(PARAMETER) REFERENCES PARAMETER(ID),
FOREIGN KEY(OUTPUT) REFERENCES OUTPUT(ID),
FOREIGN KEY(RUN) REFERENCES RUN(ID)
);



