package client;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.EventListener;
import javax.swing.*;

/**
 * Canvas represents a drawing surface that allows the user to draw
 * on it freehand, with the mouse.
 */

public class Canvas extends JPanel {
    
	// image where the user's drawing is stored
	private static final long serialVersionUID = 2L;
	private final Client client;
	private EventListener currentListener;

	public Canvas(Client client) {
    this.client = client;

    // Initialize the drawing buffer as soon as the canvas has a size
    this.addComponentListener(new java.awt.event.ComponentAdapter() {
        @Override
        public void componentResized(java.awt.event.ComponentEvent e) {
            if (getWidth() > 0 && getHeight() > 0 && client.getDrawingBuffer() == null) {
                makeDrawingBuffer();
                repaint(); // just in case
            }
        }
    });
	}
	/**
	 * @see javax.swing.JComponent#paintComponent(java.awt.Graphics)
	 */
	@Override
	public void paintComponent(Graphics g) {
		// If this is the first time paintComponent() is being called,
		// make our drawing buffer.
		if (client.getDrawingBuffer() == null) {
			makeDrawingBuffer();
		}

		// Copy the drawing buffer to the screen.
		g.drawImage(client.getDrawingBuffer(), 0, 0, null);
	}


	/**
	 * Make the drawing buffer and draw some starting content for it.
	 */
	protected void makeDrawingBuffer() {
		client.setDrawingBuffer(new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB));
		fillWithWhite();
	}

	/**
	 * Make the drawing buffer entirely white.
	 */
	protected void fillWithWhite() {
		final Graphics2D g = (Graphics2D) client.getDrawingBuffer().getGraphics();

		g.setColor(Color.WHITE);
		g.fillRect(0,  0,  getWidth(), getHeight());

		// IMPORTANT!  every time we draw on the internal drawing buffer, we
		// have to notify Swing to repaint this component on the screen.
		this.repaint();
	}

	/**
	 * Draw a line between two points (x1, y1) and (x2, y2), specified in
	 * pixels relative to the upper-left corner of the drawing buffer.
	 */
        @SuppressWarnings("CallToPrintStackTrace")
	protected void drawLineSegmentAndCall(int x1, int y1, int x2, int y2, int color, float width) {
		drawLineSegment(x1, y1, x2, y2, color, width);
		try {
			client.makeDrawRequest("drawLineSegment "+x1+" "+y1+" "+x2+" "+y2+" "+(color+16777216)+" "+width);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Draw a line between two points (x1, y1) and (x2, y2), specified in
	 * pixels relative to the upper-left corner of the drawing buffer.
	 */
	public void drawLineSegment(int x1, int y1, int x2, int y2, int color, float width) {
		Graphics2D g = (Graphics2D) client.getDrawingBuffer().getGraphics();
		Color colorObject = new Color(color);
		g.setColor(colorObject);
		g.setStroke(new BasicStroke(width));
		g.drawLine(x1, y1, x2, y2);

		// IMPORTANT!  every time we draw on the internal drawing buffer, we
		// have to notify Swing to repaint this component on the screen.
		this.repaint();
	}
	
	/**
	 * Updates the label showing the current username and the current board name
	 */
	public void updateCurrentUserBoard() {
        String user = client.getUsername();
        String board = client.getCurrentBoardName();
        client.getClientGUI().setCurrentUserBoard(new JLabel("Hi, " + user + ". This board is: " + board));
    }
    
    /**
     * Add the mouse listener that supports the user's freehand drawing.
     */
    public void addDrawingController(EventListener listener) {
        if (currentListener != null) {
            removeMouseListener((MouseListener) currentListener);
            removeMouseMotionListener((MouseMotionListener) currentListener);
        }
        currentListener = listener;
        addMouseListener((MouseListener) currentListener);
        addMouseMotionListener((MouseMotionListener) currentListener);
    }
    
    /**
     * Resets the drawing buffer to be blank and calls switch canvas on the client
     * @param board
     */
    public void switchBoard(String board) {
    	fillWithWhite();
        client.switchBoard(board);
    }
    
    public EventListener getCurrentListener() {
        return currentListener;
    }

}



