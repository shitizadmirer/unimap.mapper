-- UniMap database structure, used for the MySQL server from UPB
DROP TABLE IF EXISTS MAPPER, OUTPUT, PARAMETER, NOC_TOPOLOGY, BENCHMARK, RUN;

CREATE TABLE RUN
(
ID INTEGER PRIMARY KEY AUTO_INCREMENT
);

CREATE TABLE BENCHMARK
(
ID INTEGER NOT NULL AUTO_INCREMENT,
SUITE VARCHAR(255),
NAME VARCHAR(255),
DESCRIPTION VARCHAR(255),
CTG_ID VARCHAR(255),
PRIMARY KEY (ID)
);

CREATE TABLE NOC_TOPOLOGY
(
ID INTEGER PRIMARY KEY AUTO_INCREMENT,
NAME VARCHAR(255),
DESCRIPTION VARCHAR(255),
SIZE VARCHAR(255)
);

CREATE TABLE PARAMETER
(
ID INTEGER,
NAME VARCHAR(150),
VALUE VARCHAR(150),
PRIMARY KEY (ID, NAME, VALUE),
FOREIGN KEY(ID) REFERENCES RUN (ID) ON DELETE RESTRICT
);

CREATE TABLE OUTPUT
(
ID INTEGER,
NAME VARCHAR(150),
VALUE VARCHAR(150),
PRIMARY KEY (ID, NAME, VALUE),
FOREIGN KEY(ID) REFERENCES RUN (ID) ON DELETE RESTRICT
);

CREATE TABLE MAPPER
(
ID INTEGER PRIMARY KEY AUTO_INCREMENT,
NAME VARCHAR(255),
DESCRIPTION VARCHAR(255),
BENCHMARK INTEGER,
APCG_ID VARCHAR(255),
NOC_TOPOLOGY INTEGER,
MAPPING_XML TEXT,
START_DATETIME DATETIME,
REAL_TIME REAL,
USER_TIME REAL,
SYS_TIME REAL,
AVG_HEAP_MEMORY REAL,
AVG_HEAP_MEMORY_CHART BLOB,
RUN INTEGER,
FOREIGN KEY (BENCHMARK) REFERENCES BENCHMARK (ID) ON DELETE RESTRICT,
FOREIGN KEY (NOC_TOPOLOGY) REFERENCES NOC_TOPOLOGY (ID) ON DELETE RESTRICT,
FOREIGN KEY (RUN) REFERENCES RUN (ID) ON DELETE RESTRICT
);
