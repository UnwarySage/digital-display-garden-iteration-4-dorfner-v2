package umm3601;

import org.bson.Document;
import spark.Route;
import spark.utils.IOUtils;
import com.mongodb.util.JSON;
import umm3601.digitalDisplayGarden.Authentication.Auth;
import umm3601.digitalDisplayGarden.Authentication.Cookie;
import umm3601.digitalDisplayGarden.Authentication.UnauthorizedUserException;
import umm3601.digitalDisplayGarden.Photos;
import umm3601.digitalDisplayGarden.PlantController;

import java.io.*;
import java.awt.image.RenderedImage;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import static spark.Spark.*;

import umm3601.digitalDisplayGarden.ExcelParser;
import umm3601.digitalDisplayGarden.QRCodes;

import javax.imageio.ImageIO;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;


public class Server {

    public static String databaseName = "test";

    private static String excelTempDir = "/tmp/digital-display-garden";

    private static String clientId;
    private static String clientSecret;
    private static  String publicURL;
    private static String callbackURL;

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

        String configFileLocation;
        if (args.length == 0) {
            configFileLocation = "config.properties";
        } else {
            configFileLocation = args[0];
        }
        readConfig(configFileLocation);

        port(2538);

        // This users looks in the folder `public` for the static web artifacts,
        // which includes all the HTML, CSS, and JS files generated by the Angular
        // build. This `public` directory _must_ be somewhere in the classpath;
        // a problem which is resolved in `server/build.gradle`.
        staticFiles.location("/public");

        PlantController plantController = new PlantController(databaseName);
        Photos photos = new Photos(databaseName);
        Auth auth = new Auth(clientId, clientSecret, callbackURL);

        options("/*", (request, response) -> {

            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }
 
            return "OK";
        });

        before((request, response) -> {
            response.header("Access-Control-Allow-Credentials", "true");
            response.header("Access-Control-Allow-Origin", publicURL);
        });

        // Redirects for the "home" page
        redirect.get("", "/");

        Route clientRoute = (req, res) -> {
            InputStream stream = plantController.getClass().getResourceAsStream("/public/index.html");
            return IOUtils.toString(stream);
        };
        Route notFoundRoute = (req, res) -> {
            res.type("text");
            res.status(404);
            return "Sorry, we couldn't find that!";
        };

        get("/", clientRoute);

        get("callback", (req, res) ->{
           Map<String, String[]> params = req.queryMap().toMap();
           String[] states = params.get("state");
           String[] codes = params.get("code");
           String[] errors = params.get("error");
           if (null == states) {
               // we REQUIRE that we be passed a state
               halt(400);
               return ""; // never reached
           }
           if (null == codes ) {
               if (null == errors) {
                   // we don't have codes, but we don't have an error either, so this a garbage request
                   halt(400);
                   return ""; // never reached
               }
               else if ("access_denied".equals(errors[0])) {
                   // the user clicked "deny", so send them to the visitor page
                   res.redirect(publicURL);
                   return ""; // send an empty body back on redirect
               }
               else {
                   // an unknown error was passed to us, so we halt
                   halt(400);
                   return ""; // not reached
               }
           }
           String state = states[0];
           String code = codes[0];
            try {
                String originatingURL = auth.verifyCallBack(state, code);
                if (null != originatingURL) {
                    Cookie c = auth.getCookie();
                    res.cookie(c.name, c.value, c.max_age);
                    res.redirect(originatingURL);
                    System.out.println("good");
                    return ""; // not reached
                } else {
                    System.out.println("bad");
                    res.status(403);
                    return "?????"; // todo: return a reasonable message
                }
            } catch (UnauthorizedUserException e) {
                res.redirect(publicURL + "/admin/incorrectAccount");
                return ""; // not reached
            }
        });

        get("api/check-authorization", (req, res) -> {
            res.type("application/json");
            String cookie = req.cookie("ddg");
            Document returnDoc = new Document();
            returnDoc.append("authorized", auth.authorized(cookie));
            return JSON.serialize(returnDoc);
        });

        get("api/authorize", (req,res) -> {
            String originatingURLs[] = req.queryMap().toMap().get("originatingURL");
            String originatingURL;
            if (originatingURLs == null) {
                originatingURL = publicURL;
            } else {
                originatingURL = originatingURLs[0];
            }
            res.redirect(auth.getAuthURL(originatingURL));
            // I think we could return an arbitrary value since the redirect prevents this from being used
            return res;
        });

        // List plants
        get("api/plants", (req, res) -> {
            res.type("application/json");
            return plantController.listPlants(req.queryMap().toMap(), plantController.getLiveUploadId());
        });

        //Get a plant
        get("api/plants/:plantID", (req, res) -> {
            res.type("application/json");
            String id = req.params("plantID");
            return plantController.getPlantByPlantID(id, plantController.getLiveUploadId(), false);
        });

        //Get a plant, WITHOUT leaving a visit count.
        get("api/plants/a/:plantID", (req, res) -> {
            res.type("application/json");
            String id = req.params("plantID");
            return plantController.getPlantByPlantID(id, plantController.getLiveUploadId(), true);
        });

        //Get feedback counts for a plant
        get("api/plants/:plantID/counts", (req, res) -> {
            res.type("application/json");
            String id = req.params("plantID");
            return plantController.getFeedbackForPlantByPlantID(id, plantController.getLiveUploadId());
        });

        //List all Beds
        get("api/gardenLocations", (req, res) -> {
            res.type("application/json");
            return plantController.getGardenLocationsAsJson(plantController.getLiveUploadId());
        });

        // List all uploadIds
        get("api/uploadIds", (req, res) -> {
            String cookie = req.cookie("ddg");
            if(!auth.authorized(cookie)) {
                halt(403);
            }
            res.type("application/json");
            return JSON.serialize(plantController.listUploadIds());
        });

        post("api/plants/rate", (req, res) -> {
            res.type("application/json");
            return JSON.serialize(plantController.addFlowerRating(req.body(), plantController.getLiveUploadId()));
        });

        post("api/plants/changeRate", (req, res) -> {
            res.type("application/json");
            return plantController.changeRating(req.body(), plantController.getLiveUploadId());
        });

        post("api/plants/deleteRate", (req, res) -> {
            res.type("application/json");
            return plantController.deleteRating(req.body(), plantController.getLiveUploadId());
        });

        get("api/export", (req, res) -> {
            String cookie = req.cookie("ddg");
            if(!auth.authorized(cookie)) {
                res.redirect(auth.getAuthURL(publicURL + "/admin/exportData"));
                return res; // not reached
            } else {
                res.type("application/vnd.ms-excel");
                res.header("Content-Disposition", "attachment; filename=\"plant-comments.xlsx\"");
                // Note that after flush() or close() is called on
                // res.raw().getOutputStream(), the response can no longer be
                // modified. Since exportCollectedData(..) closes the OutputStream
                // when it is done, it needs to be the last line of this function.
                plantController.exportCollectedData(res.raw().getOutputStream(), req.queryMap().toMap().get("uploadId")[0]);
                return res;
            }
        });

        get("api/plants/:plantID/getImage", (req,res) ->{
           res.type("application/png");
           plantController.getImage(res.raw().getOutputStream(),
                                    req.params("plantID"),
                                   plantController.getLiveUploadId());
           return res;
        });

        get("api/liveUploadId", (req, res) -> {
            String cookie = req.cookie("ddg");
            if(!auth.authorized(cookie)) {
                halt(403);
            }
            res.type("application/json");
            return JSON.serialize(plantController.getLiveUploadId());
        });



        get("api/qrcodes", (req, res) -> {
            String cookie = req.cookie("ddg");
            if(!auth.authorized(cookie)) {
                res.redirect(auth.getAuthURL(publicURL + "/admin"));
            }
            res.type("application/zip");

            String liveUploadID = plantController.getLiveUploadId();
            System.err.println("liveUploadID=" + liveUploadID);
            String zipPath = QRCodes.CreateQRCodes(
                    liveUploadID,
                    plantController.getGardenLocations(liveUploadID),
                    publicURL + "/bed/");
            System.err.println(zipPath);
            if(zipPath == null)
                return null;

            res.header("Content-Disposition","attachment; filename=\"" + zipPath + "\"");

            //Get bytes from the file
            File zipFile = new File(zipPath);
            byte[] bytes = spark.utils.IOUtils.toByteArray(new FileInputStream(zipFile));

            //Delete local .zip file
            Files.delete(Paths.get(zipPath));

            return bytes;
        });

        // Posting a comment
        post("api/plants/leaveComment", (req, res) -> {
            res.type("application/json");
            return plantController.addComment(req.body(), plantController.getLiveUploadId());
        });

        // Accept an xls file
        post("api/import", (req, res) -> {
            String cookie = req.cookie("ddg");
            if(!auth.authorized(cookie)) {
                halt(403);
            }
            res.type("application/json");
            try {

                MultipartConfigElement multipartConfigElement = new MultipartConfigElement(excelTempDir);
                req.raw().setAttribute("org.eclipse.jetty.multipartConfig", multipartConfigElement);

                String fileName = Long.valueOf(System.currentTimeMillis()).toString();
                Part part = req.raw().getPart("file[]");

                ExcelParser parser = new ExcelParser(part.getInputStream(), databaseName);

                String id = ExcelParser.getAvailableUploadId();
                parser.parseExcel(id);

                return JSON.serialize(id);

            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }

        });

        post("api/plants/:plantID/importImage",(req,res) ->{

            res.type("application/json");

            MultipartConfigElement multipartConfigElement = new MultipartConfigElement(excelTempDir);
            req.raw().setAttribute("org.eclipse.jetty.multipartConfig", multipartConfigElement);
            Part part = req.raw().getPart("file[]");
            RenderedImage photo = ImageIO.read(part.getInputStream());

            String id = req.params("plantID");

            return photos.saveImage(id, photo, plantController.getLiveUploadId());
        });

        delete("api/deleteData/:uploadId", (req, res) -> {
            String cookie = req.cookie("ddg");
            if(!auth.authorized(cookie)) {
                halt(403);
            }
            res.type("application/json");
            String uploadID = req.params("uploadId");
            try {
                return JSON.serialize(plantController.deleteUploadID(uploadID));
            } catch (IllegalStateException e) {
                Document failureStatus = new Document();
                failureStatus.append("message", e.getMessage());
                res.status(400);
                return JSON.serialize(failureStatus);
            }
        });

        // requests starting with 'api' should always be handled
        // by Spark, so if we haven't found a match yet we
        // return a 404 error
        get("api/*", notFoundRoute);

        get("/*", clientRoute);

        exception(Exception.class, (exception, request, response) -> {
            System.err.println("(" + new Date() + ") Unhandled exception occurred while processing request:");
            String queryString = request.queryString();
            if (null == queryString) {
                queryString = "";
            }
            System.err.println(request.requestMethod() + " " + request.url() + queryString);
            System.err.println("Full StackTrace:");
            exception.printStackTrace();

            response.type("application/json");
            response.status(500);
            Document d = new Document();
            d.append("exception", exception.getClass().toString());
            d.append("message", exception.getMessage());
            response.body(d.toJson());
        });

        // Handle "404" file not found requests:
        notFound(notFoundRoute);
    }

    public static void readConfig(String configFileLocation) {
        try {
            InputStream input = new FileInputStream(configFileLocation);
            Properties props = new Properties();
            props.load(input);

            clientId = props.getProperty("clientID");
            if (null == clientId) {
                System.err.println("Could not read Google OAuth Client ID (clientID) from properties file");
            }
            clientSecret = props.getProperty("clientSecret");
            if (null == clientSecret) {
                System.err.println("Could not read Google OAuth Client secret (clientSecretz) from properties file");
            }
            publicURL = props.getProperty("publicURL");
            if (null == publicURL) {
                System.err.println("Could not read what url visitors access us at from the properties file");
            }
            callbackURL = props.getProperty("callbackURL");
            if (null == publicURL) {
                System.err.println("Could not read what url to use for callback from the properties file");
            }

        } catch (FileNotFoundException e) {
            System.err.println("Failed to open the config file for reading");
            System.exit(1);
        } catch (IOException e) {
            System.out.println("Failed to read the config file");
            System.exit(1);
        }
    }
}
