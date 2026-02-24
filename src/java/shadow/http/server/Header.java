package shadow.http.server;

public class Header {
    public String nameIn;
    public String name;
    public String value;

    public Header(String nameIn, String name, String value) {
        this.nameIn = nameIn;
        this.name = name;
        this.value = value;
    }
}