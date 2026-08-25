package com.example.databasecopier.dump

import com.example.databasecopier.adapter.SourceAdapter
import java.io.File

class MysqlDumpSourceAdapter(file: File) : SourceAdapter by DumpSourceAdapter(file, DumpDialect.MYSQL)
