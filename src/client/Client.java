package client;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Enumeration;
import java.net.NetworkInterface;
import java.net.InetAddress;

import javax.swing.SwingUtilities;

import command.Command;

public class Client {

	// Default port for the server
	private static final int DEFAULT_PORT = 4444;

	//the username the client will go by in this session
	//must be unique; no other clients can have this user name
	private String username;
	//the name of the board currently being drawn upon
	private String currentBoardName;
	//the color the user is currently drawing in
	private Color currentColor = Color.BLACK;
	//the width of the brush the user is currently drawing with
	private float currentWidth = 10;
	private BufferedImage drawingBuffer;

	// used for server-client communications:
	// All data updated by server requests must also have a tracker as to whether it 
	// has been updated.  All server responses are handled in a seperate thread so as
	// to enable real time updates.  This means that each request for an update must 
	// wait on these tracker variables to confirm that the server has responded with
	// the updated information
	private String[] boards = {};
	private final long timeoutLength = 500L;
	private Tracker boardsUpdated = new Tracker(false);
	private Hashtable<String, Tracker> newBoardMade = new Hashtable<String, Tracker>();
	private Hashtable<String, Tracker> newBoardSuccessful = new Hashtable<String, Tracker>();
	private Tracker userCheckMade = new Tracker(false);
	private Tracker usersUpdated = new Tracker(false);
	private String[] users = {};
	private Tracker exitComplete = new Tracker(false);
	private boolean isErasing;

	//the socket with which the user connects to the client
	private Socket socket;
	BufferedReader in;
	PrintWriter out;
	ClientReceiveProtocol receiveProtocol;
	Thread receiveThread;
	
	// Flag to track if client is connected to server
	private boolean isConnected = false;

	private ClientGUI clientGUI;

	/**
	 * Creates a whiteboard client without connecting to server immediately.
	 * Connection will be established later using connectWithPin method.
	 */
	public Client() {
		addShutdownHook();
	}
	
	/**
	 * Connects to server using a 6-digit PIN.
	 * Constructs the full server IP by taking the client's IP and replacing
	 * the last two octets with the ones derived from the PIN.
	 * 
	 * @param pin the 6-digit PIN from the server GUI
	 * @throws UnknownHostException
	 * @throws IOException
	 */
	public void connectWithPin(String pin) throws UnknownHostException, IOException {
		// Get client's local IP address
		String clientIP = getLocalIPAddress();
		
		// Parse the 6-digit PIN
		String part3 = Integer.toString(Integer.parseInt(pin.substring(0, 3)));
		String part4 = Integer.toString(Integer.parseInt(pin.substring(3, 6)));

		// Construct the server IP
		String serverIP = clientIP.substring(0, clientIP.indexOf('.', clientIP.indexOf('.') + 1)) + "." + part3 + "." + part4;
		
		System.out.println("Client IP Subnet: " + clientIP.substring(0, clientIP.indexOf('.', clientIP.indexOf('.') + 1)));
		System.out.println("PIN: " + pin + " -> Parts: " + part3 + "." + part4);
		System.out.println("Connecting to constructed server IP: " + serverIP);
		
		// Connect to the server with timeout
		socket = new Socket();
		socket.connect(new java.net.InetSocketAddress(serverIP, DEFAULT_PORT), 5000); // 5 second timeout
		in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		out = new PrintWriter(socket.getOutputStream(), true);
		receiveProtocol = new ClientReceiveProtocol(in, this);
		receiveThread = new Thread(receiveProtocol);
		receiveThread.start();
		
		// Mark as connected
		isConnected = true;
	}
	
	/**
	 * Gets the local IP address of this client machine by intelligently filtering out virtual interfaces.
	 * @return the local IP address as a string
	 */
	private String getLocalIPAddress() {
		try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                String interfaceName = networkInterface.getDisplayName().toLowerCase();

                // Skip loopback, virtual, and disabled interfaces
                if (networkInterface.isLoopback() || networkInterface.isVirtual() || !networkInterface.isUp() || 
                    interfaceName.contains("virtual") || interfaceName.contains("vmnet") || interfaceName.contains("vbox")) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    // Return the first valid IPv4 address found
                    if (addr.getAddress().length == 4 && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
		} catch (Exception e) {
			System.err.println("Error getting IP address: " + e.getMessage());
		}
		// Fallback to localhost if we can't get the actual IP
		return "127.0.0.1";
	}
	
	/**
	 * Checks if the client is connected to the server
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		return isConnected;
	}

	/**
	 * Starts a whiteboard client connected to host on the given port.
	 * 
	 * @param host
	 * @param port
	 * @throws UnknownHostException
	 * @throws IOException
	 */
	public Client(String host) throws UnknownHostException, IOException {
		socket = new Socket(host, DEFAULT_PORT);
		in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		out = new PrintWriter(socket.getOutputStream(), true);
		receiveProtocol = new ClientReceiveProtocol(in, this);
		receiveThread = new Thread(receiveProtocol);
		receiveThread.start();
		addShutdownHook();
	}
	
	/**
	 * Starts the client's GUI
	 */
	public void startGUI() {
		clientGUI = new ClientGUI(this);
	}


	/**
	 * Checks with the server to make sure the username hasn't already been taken and if it hasn't, create the user
	 * @param username: the user's choice of username
	 * @return: true if username creation is successful, false if not
	 */
	public boolean createUser(String username, String boardName) throws Exception {
		// Make request and wait for a response
		userCheckMade.setValue(false);
		makeRequest("checkAndAddUser "+username+" "+boardName).join();
		timeout(userCheckMade, timeoutLength, "check and add user");

		return (this.username != null && currentBoardName != null);
	}

	/**
	 * Parses and stores response from server after newUser request is made
	 * @param response: String response from the server
	 * @throws Exception
	 */
	public void parseNewUserFromServerResponse(String response) throws Exception {
		String[] elements = response.split(" ");
		if(elements[0]!="check"&& elements.length!=4) {
			throw new Exception("Server returned unexpected result: " + response);
		}

		boolean created = Boolean.valueOf(elements[3]);
		if (created) {
			this.username = elements[1];
			this.currentBoardName = elements[2];
		}
		userCheckMade.setValue(true);
	}


	/**
	 * Switches the current board to the board with the given name
	 * server switch command
	 * Updates the current users of the canvas
	 * @param newBoardName: the name of the new board
	 */
	public void switchBoard(String newBoardName) {
		try {
			makeRequest("switch "+username+" "+currentBoardName+" "+newBoardName);
			currentBoardName = newBoardName;
			getCanvas().updateCurrentUserBoard();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}


	/**
	 * Makes request to draw on the server
	 * @param command
	 * @throws IOException
	 */
	public void makeDrawRequest(String command) throws IOException {
		makeRequest("draw "+currentBoardName+" "+command);
	}

	/**
	 * Check that the boardName and currentBoardName are the same and then perform the command on the canvas
	 * @param boardName: the board this command is for
	 * @param command: the command to perform on the canvas
	 */
	public void commandCanvas(String boardName, Command command) {
		if (command.checkBoardName(boardName)) {
			command.invokeCommand(getCanvas());
		}
	}

	/**
	 * invokes command on canvas
	 * @param command: command to be applied to canvas
	 */
	public void applyCommand(Command command) {
		command.invokeCommand(getCanvas());
	}




	/**
	 * Checks that the board name hasn't already been taken and if it hasn't,
	 * creates a new board on the server and names it with the given name
	 * 
	 * @param newBoardName
	 *            the name to name the new board with
	 * @return true if the board creation is successful, false if not
	 */
	public boolean newBoard(String newBoardName) throws Exception {
		if (newBoardMade.containsKey(newBoardName)) return false;

		// make request and wait for response
		newBoardMade.put(newBoardName, new Tracker(false));
		newBoardSuccessful.put(newBoardName, new Tracker(true));
		makeRequest("newBoard "+newBoardName).join();
		timeout(newBoardMade.get(newBoardName), timeoutLength, "new board");

		// check if we got a successful response
		boolean successful = newBoardSuccessful.get(newBoardName).getValue();
		newBoardMade.remove(newBoardName);
		newBoardSuccessful.remove(newBoardName);
		return successful;
	}

	/**
	 * Parses and stores response from server after new board request is made
	 * @param response: response from server
	 * @throws Exception
	 */
	public void parseNewBoardFromServerResponse(String response) throws Exception {
		if(!response.contains("newBoard")) {
			throw new Exception("Server returned unexpected result: " + response);
		}
		String[] elements = response.split(" ");
		String boardName = elements[1];
		boolean successful = Boolean.valueOf(elements[2]);

		Tracker nbs = newBoardSuccessful.get(boardName);
		if (!(nbs == null)) { nbs.setValue(successful); }
		Tracker nbm = newBoardMade.get(boardName);
		if (!(nbm == null)) { nbm.setValue(true); }
	}

	/**
	 * Gets the users for the current board from the server and sets them
	 */
	public String[] getUsers() throws Exception {
		// Make request for users and wait for response
		usersUpdated.setValue(false);
		makeRequest("users "+currentBoardName);
		timeout(usersUpdated, timeoutLength, "Updateing Users");

		return users;
	}

	/**
	 * Sets users
	 * @param newUsers
	 */
	public void setUsers(String[] newUsers) {
		users = newUsers;
		usersUpdated.setValue(true);
	}

	/**
	 * Parses and stores response from server after users request is made
	 * @param response: response from server
	 * @return: String[] of users on baord
	 * @throws Exception
	 */
	public String[] parseUsersFromServerResponse(String response) throws Exception {
		String[] elements = response.split(" ");
		if(!elements[0].equals("users")) {
			throw new Exception("Server returned unexpected result: " + response);
		}
		return Arrays.copyOfRange(elements, 2, elements.length);
	}




	/**
	 * Gets new boards from the server.  Makes an update request and returns the results.
	 * @return: String[] of board names currently stored on the server
	 * @throws Exception
	 */
	public String[] getBoards() throws Exception {

		boardsUpdated.setValue(false);
		// make request for board update and wait for it to finish
		makeRequest("boards").join();
		timeout(boardsUpdated, timeoutLength, "boards update");

		// boards by now will have either been updated, or if it times out
		// then it will return what it last had
		return this.boards;

	}

	/**
	 * Sets current baords on server to newBoards
	 * @param newBoards: String[] of boards to set as newBoards
	 */
	public void setBoards(String[] newBoards) {
		boards = newBoards;
		boardsUpdated.setValue(true);
	}

	/**
	 *     
	 * Parses and stores response from server after boards request is made
	 * @param response: response from server
	 * @return: String[] of boards on server
	 * @throws Exception
	 */
	public String[] parseBoardsFromServerResponse(String response) throws Exception {

		if(!response.contains("boards")) {
			throw new Exception("Server returned unexpected result: " + response);
		}

		String[] boardsListStrings = response.split(" ");
		return Arrays.copyOfRange(boardsListStrings, 1, boardsListStrings.length);
	}



	/**
	 * Timeout function.  Waits on Tracker passed in to change to true, or returns after
	 * a certain timeout length and prints a timeout error.  Returns true if it does 
	 * time out, false otherwise.
	 * 
	 * @param variable: Tracker object to watch for a change to true
	 * @param timeoutLength: Time to wait before timing out
	 * @param timeoutMessage: Message to append to error log in event of time out
	 * @return: true if it times out, false if variable becomes true before then
	 */
	public boolean timeout(Tracker variable, long timeoutLength, String timeoutMessage) {
		long startTime = System.currentTimeMillis();

		// Keep going until the variable becomes true or we timeout
		while(!variable.getValue()) {
			long currentTime = System.currentTimeMillis();
			if(currentTime >= startTime + timeoutLength) {
				System.err.println("Timeout on: " + timeoutMessage);
				return true;
			}
		}

		return false;
	}

	/**
	 * Makes request passed in to server
	 * @param request: String of request you want to send
	 * @return: Thread with request running in it
	 * @throws IOException
	 */
	public Thread makeRequest(String request) throws IOException {

		Thread requestThread = new Thread(new ClientSendProtocol(out, request));
		requestThread.start();

		return requestThread;
	}

	/**
	 * Checks if currentBoard is equal to boardName passed in 
	 * @param boardName: boardName to check
	 * @return: true if boardName is equal to currentBoardName stored in Client
	 */
	public boolean checkForCorrectBoard(String boardName) {
		return boardName.equals(currentBoardName);
	}


	// Basic getters and setters:

	/**
	 * Get GUI for client
	 * @return
	 */
	public ClientGUI getClientGUI() {
		return clientGUI;
	}

	/**
	 * Gets the current color to use for drawing a line segment on the canvas
	 * @return the currentColor being used to draw
	 */
	public Color getCurrentColor() {
		return currentColor;
	}

	/**
	 * Gets the current width to use for drawing a line segment on the canvas
	 * @return the currentWidth being used to draw
	 */
	public float getCurrentWidth() {
		return currentWidth;
	}

	/**
	 * Sets the newWidth, probably based off of a slider movement on the canvas
	 * @param newWidth: the new width of the stroke
	 */
	public void setCurrentWidth(float newWidth) {
		currentWidth = newWidth;
	}

	/**
	 * Sets the newColor
	 * @param newWidth: the new color of the stroke
	 */
	public void setCurrentColor(Color newColor) {
		currentColor = newColor;
	}

	/**
	 * Sets username
	 * @param username: new username
	 */
	public void setUsername(String username) {
		this.username = username;
	}

	/**
	 * Sets current board name
	 * @param currentBoardName: new name to store as current baord name
	 */
	public void setCurrentBoardName(String currentBoardName) {
		this.currentBoardName = currentBoardName;
	}

	/**
	 * Gets current board name
	 * @return: current board name
	 */
	public String getCurrentBoardName() {
		return currentBoardName;
	}

	/**
	 * Returns canvas
	 * @return
	 */
	public Canvas getCanvas() {
		return clientGUI.getCanvas();
	}

	/**
	 * Returns username
	 * @return
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * Getter for drawingBuffer
	 * @return
	 */
	public BufferedImage getDrawingBuffer() {
		return drawingBuffer;
	}

	/**
	 * Setter for drawingBuffer
	 * @param newImage
	 */
	public void setDrawingBuffer(BufferedImage newImage) {
		drawingBuffer = newImage;
	}

	/**
	 * Set isErasing variable to newIsErasing
	 * @param newIsErasing: boolean to set isErasing to
	 */
	public void setIsErasing(boolean newIsErasing) {
		isErasing = newIsErasing;
	}

	/**
	 * Getter for isErasing
	 * @return: true if isErasing is set to true
	 */
	public boolean isErasing() {
		return isErasing;
	}

	/**
	 * Confirms exit of client from server
	 */
	public void completeExit() {
		exitComplete.setValue(true);
	}
	
	public void kill() {
		try {
			// kill receiving thread and wait for it to close out
			if (username!= null) {
				try {
					exitComplete.setValue(false);
					makeRequest("exit "+username).join();
					timeout(exitComplete, timeoutLength, "Exiting");
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			// Only kill receiveProtocol if it exists (client is connected)
			if (receiveProtocol != null) {
				receiveProtocol.kill();
			}

			// Only close socket if it exists and is not already closed
			if (socket != null && !socket.isClosed()) {
				socket.shutdownInput();
				socket.shutdownOutput();
				socket.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Adds commands to shutdown sequence in order to close Client gracefully
	 */
	public void addShutdownHook() {
		// close socket on exit
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			@Override
			public void run()
			{
				kill();
			}
		});
	}

    /**
     * For testing purposes. Gets the ClientReceiveProtocol
     * @param args
     */
    public ClientReceiveProtocol getClientReceiveProtocol() { 
        return receiveProtocol;
    }
    
    /**
     * Testing purposes for new board method
     * @return
     */
    public Hashtable<String, Tracker> getBoardSuccessful() {
        return newBoardSuccessful;
    }
    
    /**
     * Get exitComplete
     */
    public Tracker getExitComplete() {
        return exitComplete;
    }
    
    /*
     * Main program. Make a window containing a Canvas.
     */
    public static void main(String[] args) {
        // set up the UI (on the event-handling thread)
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
					Client client = new Client(); // Use new constructor that doesn't connect immediately
					client.startGUI();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}
}
