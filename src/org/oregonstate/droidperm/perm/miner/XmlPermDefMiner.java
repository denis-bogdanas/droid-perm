package org.oregonstate.droidperm.perm.miner;

import org.oregonstate.droidperm.perm.miner.jaxb_in.JaxbAnnotation;
import org.oregonstate.droidperm.perm.miner.jaxb_in.JaxbItem;
import org.oregonstate.droidperm.perm.miner.jaxb_in.JaxbItemList;
import org.oregonstate.droidperm.perm.miner.jaxb_in.JaxbVal;
import org.oregonstate.droidperm.perm.miner.jaxb_out.*;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * @author George Harder <harderg@oregonstate.edu> Created on 6/14/2016.
 */
public class XmlPermDefMiner {

    /**
     * This function takes a .xml and turns it into  list of java objects
     *
     * @param file .xml file to be turned into objects
     * @return A JaxbItemList object
     */
    public static JaxbItemList unmarshallXML(File file) throws JAXBException {

        JAXBContext jbContext = JAXBContext.newInstance(JaxbItemList.class);
        Unmarshaller unmarshaller = jbContext.createUnmarshaller();

        return (JaxbItemList) unmarshaller.unmarshal(file);
    }

    public static JaxbItemList unmarshallXMLFromIO(InputStream inputStream) throws JAXBException {

        JAXBContext jbContext = JAXBContext.newInstance(JaxbItemList.class);
        Unmarshaller unmarshaller = jbContext.createUnmarshaller();

        return (JaxbItemList) unmarshaller.unmarshal(inputStream);
    }

    /**
     * This takes a JaxbItemList object and marshalls it into a .xml file
     *
     * @param data JaxbItemList to be marshalled
     * @param file file to marshall the data into
     */
    public static void marshallXML(JaxbItemList data, File file) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(JaxbItemList.class);
        Marshaller jbMarshaller = jaxbContext.createMarshaller();

        jbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

        jbMarshaller.marshal(data, file);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void save(PermissionDefList permissionDefList, File file) throws JAXBException, IOException {
        JAXBContext jaxbContext = JAXBContext.newInstance(PermissionDefList.class);
        Marshaller marshaller = jaxbContext.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

        Path path = file.toPath();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        marshaller.marshal(permissionDefList, file);
    }

    public static PermissionDefList unmarshallPermDefs(File file) throws JAXBException {
        JAXBContext jbContext = JAXBContext.newInstance(PermissionDefList.class);
        Unmarshaller unmarshaller = jbContext.createUnmarshaller();
        return (PermissionDefList) unmarshaller.unmarshal(file);
    }

    /**
     * This function takes a JaxbItemList and filters out all of the items that do not have the RequiresPermission
     * annotation
     *
     * @return a filtered JaxbItemList
     */
    public static JaxbItemList filterItemList(JaxbItemList unfilteredItems) throws JAXBException {
        JaxbItemList filteredItems = new JaxbItemList();
        List<JaxbItem> newList = unfilteredItems.getItems().stream().filter(item ->
                item.getAnnotations().stream().anyMatch(anno -> anno.getName().contains("RequiresPermission"))
        ).collect(Collectors.toList());
        filteredItems.setItems(newList);

        return filteredItems;
    }

    static void extractPermissionDefs(String androidAnnotationsLocation, File saveFile)
            throws JAXBException, IOException {
        XmlPermDefMiner m = new XmlPermDefMiner();
        JaxbItemList jaxbItemList;
        PermissionDefList permissionDefList;
        jaxbItemList = m.combineItems(androidAnnotationsLocation);
        jaxbItemList = filterItemList(jaxbItemList);
        permissionDefList = m.buildPermissionDefList(jaxbItemList);

        save(permissionDefList, saveFile);
    }

    /**
     * This functions iterates through each of the annotations.xml files that are in the annotationFiles list and
     * unmarshalls the xml in each of the files thus turning them into JaxbItemList objects. It store these lists in a
     * List object, then iterates through this list of lists and combines each JaxbItem in every list into a single
     * combined JaxbItemList. This combined list can then be marshalled into a single xml file.
     *
     * @return JaxbItemList - a single object containing all of the items in the annotations files
     */
    public JaxbItemList combineItems(String androidAnnotationsLocation) throws JAXBException, IOException {
        List<JaxbItemList> jaxbItemLists = getXMLFromIOStream(androidAnnotationsLocation);
        JaxbItemList result = new JaxbItemList();

        List<JaxbItem> combinedItems =
                jaxbItemLists.stream().flatMap(l -> l.getItems().stream()).collect(Collectors.toList());
        result.setItems(combinedItems);
        return result;
    }

    public PermissionDefList buildPermissionDefList(JaxbItemList items) {
        String delimiters = "[ ]+";
        PermissionDefList permissionDefList = new PermissionDefList();

        for (JaxbItem jaxbItem : items.getItems()) {
            PermissionDef permissionDef = new PermissionDef();

            //Break up the string on spaces
            //The string before the first space is the class name, what comes after is the method or field value
            String[] tokens = jaxbItem.getName().split(delimiters);

            String javaClassName = tokens[0];
            permissionDef.setClassName(processInnerClasses(javaClassName));

            //Here the method or field is rebuilt because it was likely broken by the previous split
            StringBuilder firstBuilder = new StringBuilder();
            for (int i = 1; i < tokens.length; i++) {
                firstBuilder.append(tokens[i]).append(" ");
            }

            //This check ensure that any Java generics information is removed from the string
            if (firstBuilder.toString().contains("<")) {
                firstBuilder = scrubJavaGenerics(firstBuilder);
            }
            String beforeProcessingInner = firstBuilder.toString().trim();
            String signature = processInnerClasses(beforeProcessingInner);
            permissionDef.setTargetName(signature);

            //Here we determine if the target of the permission is a method or a field
            if (permissionDef.getTargetName().contains("(")
                    && permissionDef.getTargetName().contains(")")) {
                permissionDef.setTargetType(TargetType.Method);
            } else {
                permissionDef.setTargetType(TargetType.Field);
            }

            //Finally iterate through the annotations, extract the relevant information and put it in a PermDef object
            extractPermissions(permissionDef, jaxbItem);

            //some permission defs wrongly have an empty set of permissions. They have to be elliminated here.
            if (!permissionDef.getPermissions().isEmpty()) {
                permissionDefList.addPermissionDef(permissionDef);
            }
        }
        return permissionDefList;
    }

    /**
     * Replace "." with "$" when connecting inner class names.
     */
    private String processInnerClasses(String str) {
        //Match a dot, followed by an uppercase java id including $, followed by a dot.
        //Last dot should be replaced by $
        String regex = "(\\.\\p{Upper}[\\w$]*)\\.";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(str);
        while (matcher.find()) {
            str = str.replaceAll(regex, "$1\\$");
            matcher = pattern.matcher(str);
        }
        return str;
    }

    /**
     * Does the bulk of the work for buildPermissionDefList. It gets the relevant information from a JaxbAnnotation
     * object and gives it to a PermissionDef object.
     */
    private void extractPermissions(PermissionDef permissionDef, JaxbItem jaxbItem) {
        for (JaxbAnnotation jaxbAnnotation : jaxbItem.getAnnotations()) {
            //noinspection Convert2streamapi
            for (JaxbVal jaxbVal : jaxbAnnotation.getVals()) {
                //When a value has the name 'apis' its value is not a permission, so we don't store it
                if (!(jaxbVal.getName().contains("apis"))) {
                    String delims = "[{},\"]+";
                    String[] tokens = jaxbVal.getVal().split(delims);

                    for (String token : tokens) {
                        Permission permission = new Permission();
                        if (token.length() > 1) {
                            permission.setName(token.trim());

                            //This block handles the extra Read or Write tag that may be attached to a permission
                            if (jaxbAnnotation.getName().contains("Read")) {
                                permission.setOperationType(OperationType.Read);
                            }
                            if (jaxbAnnotation.getName().contains("Write")) {
                                permission.setOperationType(OperationType.Write);
                            }

                            permissionDef.addPermission(permission);
                        }
                    }

                    //This block handles the OR/AND relationship that may exist when an item has multiple permissions
                    PermissionRel permRel;
                    if (jaxbVal.getName().contains("anyOf")) {
                        permRel = PermissionRel.AnyOf;
                    } else {
                        //if there's no rel defined, it's AllOf.
                        permRel = PermissionRel.AllOf;
                    }
                    permissionDef.setPermissionRel(permRel);
                }
            }
        }
    }

    /**
     * This function removes any java generics information from the methods that require permissions. It is a helper the
     * ItemsToPermissionDef function
     */
    private StringBuilder scrubJavaGenerics(StringBuilder firstBuilder) {

        //Java generics are in greater than/less than brackets so we breakup the string on them
        String delims = "[<>]+";
        String[] token = firstBuilder.toString().split(delims);

        //When the string is broken every other token is java generics info so we skip these when rebuilding the string
        StringBuilder secondBuilder = new StringBuilder();
        for (int i = 0; i < token.length; i += 2) {
            //This check adds a space between the return type and the method signature
            if (i == 0) {
                secondBuilder.append(token[i]).append(" ");
            } else {
                secondBuilder.append(token[i]);
            }
        }
        return secondBuilder;
    }

    public List<JaxbItemList> getXMLFromIOStream(String pathToJar) throws IOException, JAXBException {
        ZipFile zipFile = new ZipFile(pathToJar);
        List<JaxbItemList> jaxbItemLists = new ArrayList<>();

        Enumeration<? extends ZipEntry> entries = zipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().contains("annotations.xml")) {
                InputStream stream = zipFile.getInputStream(entry);
                jaxbItemLists.add(unmarshallXMLFromIO(stream));
                stream.close();
            }
        }
        zipFile.close();
        return jaxbItemLists;
    }
}
