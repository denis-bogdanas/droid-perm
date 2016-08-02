package org.oregonstate.droidperm.perm;

import org.oregonstate.droidperm.perm.miner.PermMinerMain;
import org.oregonstate.droidperm.perm.miner.XmlPermDefMiner;
import org.oregonstate.droidperm.perm.miner.jaxb_out.Permission;
import org.oregonstate.droidperm.perm.miner.jaxb_out.PermissionDef;
import org.oregonstate.droidperm.perm.miner.jaxb_out.PermissionDefList;
import org.oregonstate.droidperm.perm.miner.jaxb_out.TargetType;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.data.SootMethodAndClass;

import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author George Harder <harderg@oregonstate.edu> Created on 7/28/2016.
 */
public class XMLPermissionDefParser implements IPermissionDefProvider {
    private static final int INITIAL_SET_SIZE = 10000;
    //Separating the directory and the file name allow relative pathing when saving the output
    private static final String PERMISSIONS_SAVE_DIR = "src\\test\\annotations\\";
    private static final String PERMISSIONS_SAVE_FILE_NAME = "permdefparser.xml";

    private static final String regex = "^<(.+):\\s*(.+)\\s+(.+)\\s*\\((.*)\\)>\\s+->\\s+(.+)$";
    private static final Pattern pattern = Pattern.compile(regex);

    private Set<SootMethodAndClass> permCheckerDefs = new HashSet<>(INITIAL_SET_SIZE);
    private Set<AndroidMethod> sensitiveDefs = new HashSet<>(INITIAL_SET_SIZE);

    private File xmlpermDefFile;
    private File txtPermDefFile;

    public XMLPermissionDefParser(File xmlFile, File txtFile) throws IOException {
        this.xmlpermDefFile = xmlFile;
        this.txtPermDefFile = txtFile;
        parseTxtPermissions();
        minePermDefs();
        addSensitives(getPermissionDefList(PERMISSIONS_SAVE_DIR+PERMISSIONS_SAVE_FILE_NAME));
    }

    private void minePermDefs() {
        String[] arguments = new String[3];

        arguments[0] = xmlpermDefFile.getAbsolutePath();
        arguments[1] = PERMISSIONS_SAVE_DIR;
        arguments[2] = PERMISSIONS_SAVE_FILE_NAME;

        try {
            PermMinerMain.main(arguments);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PermissionDefList getPermissionDefList(String minedDefsFilePath) {
        File minedPermDefs = new File(minedDefsFilePath);
        try {
            return XmlPermDefMiner.unmarshallPermDefs(minedPermDefs);
        } catch (JAXBException e) {
            throw new RuntimeException(e);
        }
    }

    private void addSensitives(PermissionDefList permissionDefList) {
        String delimiters = "[ \\( \\),]+";
        String returnType;
        String targetName;


        for(PermissionDef permissionDef : permissionDefList.getPermissionDefs()) {
            List<String> parameters = new ArrayList<>();
            Set<String> permissions = new HashSet<>();

            if(permissionDef.getTargetType() != TargetType.Field && !permissionDef.getPermissions().isEmpty()) {
                String[] tokens = permissionDef.getTargetName().split(delimiters);
                returnType = tokens[0];
                targetName = tokens[1];
                for(int i = 2; i < tokens.length; i++) {
                    if (tokens[i].length() > 1) {
                        parameters.add(tokens[i]);
                    }
                }

                for (Permission permission : permissionDef.getPermissions()) {
                    permissions.add(permission.getName());
                }


                sensitiveDefs.add(new AndroidMethod(targetName, parameters, returnType,
                        permissionDef.getClassName(), permissions));
            }
        }
    }

    private void parseTxtPermissions() {
        try {
            TxtPermissionDefParser txtPermissionDefParser = new TxtPermissionDefParser(this.txtPermDefFile);
            sensitiveDefs.addAll(txtPermissionDefParser.getSensitiveDefs());
            permCheckerDefs.addAll(txtPermissionDefParser.getPermCheckerDefs());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<SootMethodAndClass> getPermCheckerDefs() {
        return permCheckerDefs;
    }

    @Override
    public Set<AndroidMethod> getSensitiveDefs() {
        return sensitiveDefs;
    }
}
