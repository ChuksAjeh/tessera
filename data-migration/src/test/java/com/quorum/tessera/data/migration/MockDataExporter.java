package com.quorum.tessera.data.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

public class MockDataExporter implements DataExporter {
    
    private Map<byte[], byte[]> results;
    
    @Override
    public void export(Map<byte[], byte[]> data,Path path) throws SQLException,IOException  {
        this.results = data;
    }

    public Map<byte[], byte[]> getResults() {
        return results;
    }


 
}