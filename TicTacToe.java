import java.util.Scanner;

public class TicTacToe {

    static char[][] board = {
            {' ', ' ', ' '},
            {' ', ' ', ' '},
            {' ', ' ', ' '}
    };

    static char currentPlayer = 'X';

    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);
        boolean gameEnded = false;

        System.out.println("=== TIC TAC TOE ===");

        while (!gameEnded) {

            printBoard();
            System.out.println("Player " + currentPlayer + ", enter row (0-2) and column (0-2): ");

            int row = sc.nextInt();
            int col = sc.nextInt();

            if (isValidMove(row, col)) {
                board[row][col] = currentPlayer;

                if (checkWinner()) {
                    printBoard();
                    System.out.println("🎉 Player " + currentPlayer + " Wins!");
                    gameEnded = true;
                } else if (isBoardFull()) {
                    printBoard();
                    System.out.println("It's a Draw!");
                    gameEnded = true;
                } else {
                    switchPlayer();
                }

            } else {
                System.out.println("Invalid move! Try again.");
            }
        }

        sc.close();
    }

    static void printBoard() {
        System.out.println("-------------");
        for (int i = 0; i < 3; i++) {
            System.out.print("| ");
            for (int j = 0; j < 3; j++) {
                System.out.print(board[i][j] + " | ");
            }
            System.out.println();
            System.out.println("-------------");
        }
    }

    static boolean isValidMove(int row, int col) {
        return row >= 0 && row < 3 &&
               col >= 0 && col < 3 &&
               board[row][col] == ' ';
    }

    static void switchPlayer() {
        currentPlayer = (currentPlayer == 'X') ? 'O' : 'X';
    }

    static boolean checkWinner() {

        // Rows & Columns
        for (int i = 0; i < 3; i++) {
            if (board[i][0] == currentPlayer &&
                board[i][1] == currentPlayer &&
                board[i][2] == currentPlayer)
                return true;

            if (board[0][i] == currentPlayer &&
                board[1][i] == currentPlayer &&
                board[2][i] == currentPlayer)
                return true;
        }

        // Diagonals
        if (board[0][0] == currentPlayer &&
            board[1][1] == currentPlayer &&
            board[2][2] == currentPlayer)
            return true;

        if (board[0][2] == currentPlayer &&
            board[1][1] == currentPlayer &&
            board[2][0] == currentPlayer)
            return true;

        return false;
    }

    static boolean isBoardFull() {
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (board[i][j] == ' ')
                    return false;

        return true;
    }
}