package com.example.databasecopier.dump

import com.example.databasecopier.adapter.SourceAdapter
import java.io.File

class PostgresDumpSourceAdapter(file: File) : SourceAdapter by DumpSourceAdapter(file, DumpDialect.POSTGRESQL)
