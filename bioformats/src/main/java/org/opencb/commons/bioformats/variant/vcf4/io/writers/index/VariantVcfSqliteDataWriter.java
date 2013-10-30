package org.opencb.commons.bioformats.variant.vcf4.io.writers.index;

import com.google.common.base.Joiner;
import org.opencb.commons.bioformats.commons.SqliteSingletonConnection;
import org.opencb.commons.bioformats.feature.Genotype;
import org.opencb.commons.bioformats.variant.vcf4.VcfRecord;

import java.sql.*;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: aaleman
 * Date: 8/30/13
 * Time: 1:51 PM
 * To change this template use File | Settings | File Templates.
 */
public class VariantVcfSqliteDataWriter implements VariantDataWriter<VcfRecord> {

    private Statement stmt;
    private PreparedStatement pstmt;
    private boolean createdSampleTable;
    private SqliteSingletonConnection connection;

    public VariantVcfSqliteDataWriter(String dbName) {

        this.stmt = null;
        this.pstmt = null;
        this.createdSampleTable = false;
        this.connection = new SqliteSingletonConnection(dbName);
    }

    @Override
    public boolean open() {

        return SqliteSingletonConnection.getConnection() != null;

    }

    @Override
    public boolean close() {

        try {
            SqliteSingletonConnection.getConnection().close();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public boolean pre() {

        String variantTable = "CREATE TABLE IF NOT EXISTS variant (" +
                "id_variant INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chromosome TEXT, " +
                "position INT64, " +
                "id TEXT, " +
                "ref TEXT, " +
                "alt TEXT, " +
                "qual DOUBLE, " +
                "filter TEXT, " +
                "info TEXT, " +
                "format TEXT);";

        String sampleTable = "CREATE TABLE IF NOT EXISTS sample(" +
                "name TEXT PRIMARY KEY);";

        String sampleInfoTable = "CREATE TABLE IF NOT EXISTS sample_info(" +
                "id_sample_info INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "id_variant INTEGER, " +
                "sample_name TEXT, " +
                "allele_1 INTEGER, " +
                "allele_2 INTEGER, " +
                "data TEXT, " +
                "FOREIGN KEY(id_variant) REFERENCES variant(id_variant)," +
                "FOREIGN KEY(sample_name) REFERENCES sample(name));";
        String variantInfoTable = "CREATE TABLE IF NOT EXISTS variant_info(" +
                "id_variant_info INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "id_variant INTEGER, " +
                "key TEXT, " +
                "value TEXT, " +
                "FOREIGN KEY(id_variant) REFERENCES variant(id_variant));";

        try {
            stmt = SqliteSingletonConnection.getConnection().createStatement();

            stmt.execute(variantTable);
            stmt.execute(variantInfoTable);
            stmt.execute(sampleTable);
            stmt.execute(sampleInfoTable);

            stmt.close();

            SqliteSingletonConnection.getConnection().commit();
        } catch (SQLException e) {
            System.err.println("PRE: " + e.getClass().getName() + ": " + e.getMessage());
            return false;
        }

        return true;
    }

    @Override
    public boolean post() {

        try {

            stmt = SqliteSingletonConnection.getConnection().createStatement();
            stmt.execute("CREATE INDEX variant_chromosome_position_idx ON variant (chromosome, position);");
            stmt.execute("CREATE INDEX variant_pass_idx ON variant (filter);");
            stmt.execute("CREATE INDEX variant_id_idx ON variant (id);");
            stmt.execute("CREATE INDEX sample_name_idx ON sample (name);");
            stmt.execute("CREATE INDEX sample_info_id_variant_idx ON sample_info (id_variant);");
            stmt.execute("CREATE INDEX variant_id_variant_idx ON variant (id_variant);");
            stmt.execute("CREATE INDEX variant_info_id_variant_key_idx ON variant_info (id_variant, key);");

            stmt.execute("CREATE TABLE IF NOT EXISTS chromosome_count AS SELECT count(*) as count, chromosome from variant group by chromosome order by chromosome ASC;");

            stmt.close();
            SqliteSingletonConnection.getConnection().commit();

        } catch (SQLException e) {
            System.err.println("POST: " + e.getClass().getName() + ": " + e.getMessage());
            return false;

        }

        return true;
    }

    @Override
    public boolean writeHeader(String header) {
        return true;

    }

    @Override
    public boolean writeBatch(List<VcfRecord> data) {
        String sql, sqlSampleInfo, sqlInfo;
        PreparedStatement pstmtSample, pstmtInfo;
        String sampleName;
        String sampleData;
        int allele_1, allele_2;
        Genotype g;
        int id;
        boolean res = true;

        PreparedStatement pstmt;
        if (!createdSampleTable && data.size() > 0) {
            try {
                sql = "INSERT INTO sample (name) VALUES(?);";
                pstmt = SqliteSingletonConnection.getConnection().prepareStatement(sql);
                VcfRecord v = data.get(0);
                for (String name : v.getSampleNames()) {
                    pstmt.setString(1, name);
                    pstmt.execute();
                }

                pstmt.close();
                SqliteSingletonConnection.getConnection().commit();
                createdSampleTable = true;
            } catch (SQLException e) {
                System.err.println("SAMPLE: " + e.getClass().getName() + ": " + e.getMessage());
                res = false;
            }
        }

        sql = "INSERT INTO variant (chromosome, position, id, ref, alt, qual, filter, info, format) VALUES(?,?,?,?,?,?,?,?,?);";
        sqlSampleInfo = "INSERT INTO sample_info(id_variant, sample_name, allele_1, allele_2, data) VALUES (?,?,?,?,?);";
        sqlInfo = "INSERT INTO variant_info(id_variant, key, value) VALUES (?,?,?);";

        try {

            pstmt = SqliteSingletonConnection.getConnection().prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
            pstmtSample = SqliteSingletonConnection.getConnection().prepareStatement(sqlSampleInfo);
            pstmtInfo = SqliteSingletonConnection.getConnection().prepareStatement(sqlInfo);

            for (VcfRecord v : data) {

                pstmt.setString(1, v.getChromosome());
                pstmt.setInt(2, v.getPosition());
                pstmt.setString(3, v.getId());
                pstmt.setString(4, v.getReference());
                pstmt.setString(5, Joiner.on(",").join(v.getAltAlleles()));
                pstmt.setDouble(6, (v.getQuality().equals(".") ? 0 : Double.valueOf(v.getQuality())));
                pstmt.setString(7, v.getFilter());
                pstmt.setString(8, v.getInfo());
                pstmt.setString(9, v.getFormat());

                pstmt.execute();
                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    id = rs.getInt(1);
                    for (Map.Entry<String, String> entry : v.getSampleRawData().entrySet()) {
                        sampleName = entry.getKey();
                        sampleData = entry.getValue();

                        g = v.getSampleGenotype(sampleName);

                        allele_1 = (g.getAllele1() == null) ? -1 : g.getAllele1();
                        allele_2 = (g.getAllele2() == null) ? -1 : g.getAllele2();

                        pstmtSample.setInt(1, id);
                        pstmtSample.setString(2, sampleName);
                        pstmtSample.setInt(3, allele_1);
                        pstmtSample.setInt(4, allele_2);
                        pstmtSample.setString(5, sampleData);
                        pstmtSample.execute();

                    }

                    if (!v.getInfo().equals(".")) {
                        String[] infoFields = v.getInfo().split(";");
                        for (String elem : infoFields) {
                            String[] fields = elem.split("=");
                            pstmtInfo.setInt(1, id);
                            pstmtInfo.setString(2, fields[0]);
                            pstmtInfo.setString(3, fields[1]);
                            pstmtInfo.execute();
                        }
                    }
                } else {
                    res = false;
                }
            }

            pstmt.close();
            pstmtSample.close();
            pstmtInfo.close();
            SqliteSingletonConnection.getConnection().commit();

        } catch (SQLException e) {
            System.err.println("VARIANT/SAMPLE_INFO: " + e.getClass().getName() + ": " + e.getMessage());
            res = false;
        }

        return res;
    }

}